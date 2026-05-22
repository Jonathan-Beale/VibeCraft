package com.example.vibecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class ClaudeCommand implements CommandExecutor {

    private static final Component PREFIX = Component.text("[Claude] ", NamedTextColor.AQUA);

    private final VibeCraft plugin;
    private final PlayerSettings playerSettings;
    private final Map<String, ClaudeSession> sessions = new HashMap<>();
    private final Map<UUID, String> pendingMessages = new HashMap<>();
    private final Map<UUID, ClaudeTerminalUI> terminals = new HashMap<>();
    private final Map<UUID, List<Component>> lastResponseBook = new HashMap<>();

    private static final int CARD_PREVIEW_LINES = 4;
    private static final int MAX_PLUGIN_MESSAGE_BYTES = 30_000;
    private static final int HISTORY_CHUNK_TARGET_BYTES = 24_000;
    private static final int LEGACY_HISTORY_MAX_BYTES = 28_000;
    private static final Pattern PROJECT_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,40}$");
    private static final String MOD_MOCK_TRIGGER = "__vc_mock_stream_test__";

    public ClaudeCommand(VibeCraft plugin) {
        this.plugin = plugin;
        this.playerSettings = new PlayerSettings(plugin.getDataFolder());
    }

    public ClaudeTerminalUI getTerminal(Player player) {
        return terminals.get(player.getUniqueId());
    }

    public void removeTerminal(Player player) {
        terminals.remove(player.getUniqueId());
    }

    public void resetSession(Player player) {
        sessions.keySet().removeIf(k -> k.startsWith(player.getUniqueId().toString()));
        plugin.getPlayerData().clearSessions(player.getUniqueId());
    }

    public void cleanupPlayer(Player player) {
        UUID id = player.getUniqueId();
        sessions.keySet().removeIf(k -> k.startsWith(id.toString()));
        pendingMessages.remove(id);
        terminals.remove(id);
        lastResponseBook.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(PREFIX.append(Component.text("Operators only.", NamedTextColor.RED)));
            return true;
        }
        if (args.length == 0) {
            openTerminal(player);
            return true;
        }

        // /claude book — open the last response in a readable book GUI
        if (args[0].equalsIgnoreCase("book")) {
            List<Component> bookLines = lastResponseBook.get(player.getUniqueId());
            if (bookLines == null || bookLines.isEmpty()) {
                player.sendMessage(PREFIX.append(
                        Component.text("No response available to view.", NamedTextColor.GRAY)));
            } else {
                openResponseBook(player, bookLines);
            }
            return true;
        }

        // /claude repo [set <path>|new <name>|show]
        if (args[0].equalsIgnoreCase("repo")) {
            return handleRepo(player, args);
        }

        // /claude reset
        if (args[0].equalsIgnoreCase("reset")) {
            sessions.keySet().removeIf(k -> k.startsWith(player.getUniqueId().toString()));
            plugin.getPlayerData().clearSessions(player.getUniqueId());
            player.sendMessage(PREFIX.append(Component.text("Session reset.", NamedTextColor.GRAY)));
            return true;
        }

        boolean mockMode = args[0].equalsIgnoreCase("mock");
        int startIndex = mockMode ? 1 : 0;

        // Parse optional --path <dir> flag (anywhere in args)
        File overridePath = null;
        List<String> messageTokens = new ArrayList<>();
        for (int i = startIndex; i < args.length; i++) {
            if ((args[i].equals("--path") || args[i].equals("-p")) && i + 1 < args.length) {
                overridePath = new File(args[++i]);
            } else {
                messageTokens.add(args[i]);
            }
        }

        if (messageTokens.isEmpty()) {
            sendUsage(player);
            return true;
        }

        String message = String.join(" ", messageTokens);

        // Determine working directory
        File workDir;
        if (overridePath != null) {
            workDir = overridePath;
        } else {
            String saved = plugin.getPlayerData().getDefaultPath(player.getUniqueId());
            if (saved == null) {
                // Fall back to server-wide default-repo if configured
                String globalDefault = plugin.getDefaultRepo();
                if (globalDefault != null && !globalDefault.isBlank()) {
                    saved = globalDefault;
                    plugin.getPlayerData().setDefaultPath(player.getUniqueId(), saved);
                } else {
                    pendingMessages.put(player.getUniqueId(), message);
                    showOnboarding(player);
                    return true;
                }
            }
            workDir = new File(saved);
        }

        if (mockMode) {
            runMockClaude(player, workDir, message, overridePath != null);
        } else {
            runClaude(player, workDir, message, overridePath != null);
        }
        return true;
    }

    private void runMockClaude(Player player, File workDir, String message, boolean isOverride) {
        if (!workDir.exists()) {
            player.sendMessage(PREFIX.append(
                    Component.text("Directory not found: " + workDir.getAbsolutePath(), NamedTextColor.RED)));
            return;
        }

        final ClaudeTerminalUI terminalRef = terminals.get(player.getUniqueId());
        final boolean useTerminalUI = terminalRef != null && terminalRef.isOpen();

        if (terminalRef != null) {
            if (isOverride) terminalRef.addSystemMessage("Using: " + workDir.getName());
            terminalRef.addSystemMessage("Mock mode: streaming synthetic events (no Claude CLI call).");
            terminalRef.addUserMessage(message);
        }

        JsonObject userEvt = new JsonObject();
        userEvt.addProperty("type", "user_message");
        userEvt.addProperty("text", message);
        sendModEvent(player, userEvt.toString());

        if (!useTerminalUI && playerSettings.getBool(player.getUniqueId(), "chat.user_messages")) {
            if (isOverride)
                player.sendMessage(PREFIX.append(
                        Component.text("Using: " + workDir.getName(), NamedTextColor.GRAY)));
            player.sendMessage(Component.text("─────────────────────────────────────",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("▶ You  ", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(message, NamedTextColor.WHITE)));
        }

        List<Component> bufferedClaudeLines = new ArrayList<>();

        Consumer<TerminalEvent> terminalEvents = terminalRef == null ? null :
                event -> terminalRef.onEvent(event);

        Consumer<TerminalEvent> chatEvents = useTerminalUI ? null :
                event -> handleChatEvent(event, player, bufferedClaudeLines);

        Consumer<TerminalEvent> modEvents = event -> {
            String json = toModJson(event);
            if (json != null) sendModEvent(player, json);
        };

        List<Consumer<TerminalEvent>> allConsumers = new ArrayList<>();
        allConsumers.add(modEvents);
        if (terminalEvents != null) allConsumers.add(terminalEvents);
        if (chatEvents != null) allConsumers.add(chatEvents);
        Consumer<TerminalEvent> allEvents = event -> allConsumers.forEach(c -> c.accept(event));

        String response = "## Mock Claude Response\n"
                + "This is a simulated response for testing streaming UX only.\n\n"
                + "- No external Claude process was called\n"
                + "- Tool and thinking events are synthetic\n"
                + "- Input: " + message + "\n\n"
                + "If streaming feels smooth here but not in real mode, the bottleneck is upstream event cadence.";
        List<Component> rendered = MarkdownRenderer.render(response);

        List<TerminalEvent> scripted = new ArrayList<>();
        scripted.add(new TerminalEvent.StreamStart());

        List<String> thoughtChunks = List.of(
            "Analyzing request context and detecting active plugin target for this terminal session...",
            "Reading recent session state, UI settings, and history synchronization hints from the mod bridge...",
            "Planning event flow for stream_start, incremental thinking, tool_call, tool_result output, and final text rendering...",
            "Simulating gradual thought emission at small intervals so caret behavior, scroll behavior, and thought persistence can be validated...",
            "Checking whether thought lines remain visible after stream_end when final Claude text appears in the same history window...",
            "Preparing final response payload with markdown blocks and list formatting to stress wrapping and truncation behavior in the backtick UI...",
            "Finalizing synthetic trace: this run should feel like a real stream rather than a single delayed dump of large paragraphs..."
        );
        for (String chunk : thoughtChunks) {
            scripted.add(new TerminalEvent.Thinking(chunk));
        }

        scripted.add(new TerminalEvent.ToolCall("MockTool", "simulate_stream"));
        scripted.add(new TerminalEvent.BashOutput(List.of("mock: stream active", "mock: no CLI process launched")));
        scripted.add(new TerminalEvent.ClaudeText(rendered, response));
        scripted.add(new TerminalEvent.StreamEnd());

        long delay = 0L;
        for (TerminalEvent event : scripted) {
            final TerminalEvent evt = event;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> allEvents.accept(evt), delay);
            delay += 6L;
        }
    }

    private boolean handleRepo(Player player, String[] args) {
        if (args.length == 1 || args[1].equalsIgnoreCase("show")) {
            String path = plugin.getPlayerData().getDefaultPath(player.getUniqueId());
            player.sendMessage(PREFIX.append(path == null
                    ? Component.text("No default repo set.", NamedTextColor.YELLOW)
                    : Component.text("Default repo: " + path, NamedTextColor.WHITE)));
            return true;
        }

        if (args[1].equalsIgnoreCase("set") && args.length >= 3) {
            String path = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            setRepo(player, new File(path));
            return true;
        }

        if (args[1].equalsIgnoreCase("new") && args.length >= 3) {
            String name = args[2].trim();
            if (!PROJECT_NAME_PATTERN.matcher(name).matches()) {
                player.sendMessage(PREFIX.append(Component.text(
                        "Invalid project name. Use letters/numbers/_/- and start with a letter.",
                        NamedTextColor.RED)));
                return true;
            }

            File workspace = plugin.getWorkspaceDir();
            File newProject = new File(workspace, name);
            if (!isWithinWorkspace(newProject)) {
                player.sendMessage(PREFIX.append(Component.text(
                        "Project path resolves outside the workspace.", NamedTextColor.RED)));
                return true;
            }
            if (newProject.exists()) {
                player.sendMessage(PREFIX.append(
                        Component.text("Directory already exists: " + newProject.getAbsolutePath(), NamedTextColor.RED)));
                return true;
            }

            String className = sanitizeClassName(name);
            String packageToken = sanitizePackageToken(name);
            player.sendMessage(PREFIX.append(
                    Component.text("Creating new plugin project '" + name + "'...", NamedTextColor.GRAY)));
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    // Scaffold a new plugin project by copying VibeCraft's gradle wrapper + build files
                    scaffoldNewProject(newProject, name, packageToken, className);
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        setRepo(player, newProject);
                        player.sendMessage(PREFIX.append(
                                Component.text("Project created at " + newProject.getAbsolutePath(), NamedTextColor.GREEN)));
                    });
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(PREFIX.append(
                                Component.text("Failed: " + e.getMessage(), NamedTextColor.RED))));
                }
            });
            return true;
        }

        sendUsage(player);
        return true;
    }

    /** Called when a player picks a repo (from onboarding or /claude repo set). */
    public void setRepo(Player player, File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            player.sendMessage(PREFIX.append(
                    Component.text("Directory not found: " + dir.getAbsolutePath(), NamedTextColor.RED)));
            return;
        }
        if (!isWithinWorkspace(dir)) {
            player.sendMessage(PREFIX.append(
                    Component.text("Repo must be inside workspace: " + plugin.getWorkspaceDir().getAbsolutePath(),
                            NamedTextColor.RED)));
            return;
        }

        plugin.getPlayerData().setDefaultPath(player.getUniqueId(), dir.getAbsolutePath());
        sessions.keySet().removeIf(k -> k.startsWith(player.getUniqueId().toString()));
        plugin.getBuildScripts().addPlugin(dir);
        plugin.getBuildScripts().regenerate(plugin.getPlayerData().getAllConfiguredPaths());
        player.sendMessage(PREFIX.append(
                Component.text("Default repo set to: " + dir.getAbsolutePath(), NamedTextColor.GREEN)));

        // Run the pending message if onboarding was triggered mid-command
        String pending = pendingMessages.remove(player.getUniqueId());
        if (pending != null) {
            runClaude(player, dir, pending, false);
        }
    }

    private void showOnboarding(Player player) {
        player.sendMessage(PREFIX.append(
                Component.text("No default repo set. Choose a project:", NamedTextColor.YELLOW)));

        // Suggest repos already used by other players
        List<String> existingPaths = plugin.getPlayerData().getAllConfiguredPaths();
        // Suggest repos found by scanning the workspace
        List<File> scanned = ProjectScanner.findProjects(
                plugin.getWorkspaceDir(), plugin.getSelfDir());
        Set<String> shown = new LinkedHashSet<>();
        for (File f : scanned) shown.add(f.getAbsolutePath());
        for (String p : existingPaths) shown.add(p);

        if (shown.isEmpty()) {
            player.sendMessage(Component.text("  No projects found in workspace. ", NamedTextColor.GRAY)
                    .append(makeNewProjectLink()));
        } else {
            for (String path : shown) {
                player.sendMessage(makeRepoSuggestion(path));
            }
        }

        // "Type a custom path" option using suggest-command
        String suggestCmd = "/claude repo set ";
        player.sendMessage(Component.text("  ", NamedTextColor.GRAY)
                .append(Component.text("[Enter custom path]", NamedTextColor.WHITE)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.suggestCommand(suggestCmd))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to type a path"))))
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(makeNewProjectLink()));
    }

    private Component makeRepoSuggestion(String path) {
        String name = new File(path).getName();
        return Component.text("  ", NamedTextColor.GRAY)
                .append(Component.text("[" + name + "]", NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.runCommand("/claude repo set " + path))
                        .hoverEvent(HoverEvent.showText(Component.text(path))));
    }

    private Component makeNewProjectLink() {
        return Component.text("[New project]", NamedTextColor.GREEN)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.suggestCommand("/claude repo new "))
                .hoverEvent(HoverEvent.showText(Component.text("/claude repo new <name>")));
    }

    private void openTerminal(Player player) {
        String provider = plugin.getAiProvider();
        ClaudeTerminalUI terminal = terminals.computeIfAbsent(
            player.getUniqueId(), id -> new ClaudeTerminalUI(player, plugin,
                new SessionHistory(SessionHistory.fileFor(plugin.getDataFolder(), id, provider)),
                provider));
        terminal.open();
    }

    /** Called by TerminalInputListener when the player submits a message in compose mode. */
    public void submitFromTerminal(Player player, String message) {
        String provider = plugin.getAiProvider();
        ClaudeTerminalUI terminal = terminals.computeIfAbsent(
            player.getUniqueId(), id -> new ClaudeTerminalUI(player, plugin,
                new SessionHistory(SessionHistory.fileFor(plugin.getDataFolder(), id, provider)),
                provider));
        terminal.open();

        String saved = plugin.getPlayerData().getDefaultPath(player.getUniqueId());
        if (saved == null) {
            pendingMessages.put(player.getUniqueId(), message);
            showOnboarding(player);
            return;
        }
        runClaude(player, new File(saved), message, false);
    }

    private void runClaude(Player player, File workDir, String message, boolean isOverride) {
        if (!workDir.exists()) {
            player.sendMessage(PREFIX.append(
                    Component.text("Directory not found: " + workDir.getAbsolutePath(), NamedTextColor.RED)));
            return;
        }

        String sessionKey = player.getUniqueId() + ":" + workDir.getAbsolutePath();
        ClaudeSession session = sessions.computeIfAbsent(sessionKey, k -> {
            boolean saved = plugin.getPlayerData()
                .getHasSession(player.getUniqueId(), workDir.getAbsolutePath());
            return new ClaudeSession(
            workDir,
            plugin.getClaudePath(),
            plugin.getHermesPath(),
            plugin.getAiProvider(),
            plugin.getServerPluginsDir(),
            plugin.getRestartFlagPath(),
            plugin.getBuildScripts().getServerDir(),
            plugin.getPlayerData().getAllConfiguredPaths(),
            saved
            );
        });

        // terminalRef: non-null if terminal was ever opened — always receives events for history
        // useTerminalUI: true only when terminal is currently visible — suppresses chat output
        final ClaudeTerminalUI terminalRef = terminals.get(player.getUniqueId());
        final boolean useTerminalUI = terminalRef != null && terminalRef.isOpen();

        // Track user message in terminal history regardless of whether it's open
        if (terminalRef != null) {
            if (isOverride) terminalRef.addSystemMessage("Using: " + workDir.getName());
            terminalRef.addUserMessage(message);
        }

        // Notify mod of user message now (before stream_start) so it appears exactly once
        JsonObject userEvt = new JsonObject();
        userEvt.addProperty("type", "user_message");
        userEvt.addProperty("text", message);
        sendModEvent(player, userEvt.toString());

        // Chat output only when terminal is not visible and setting allows it
        if (!useTerminalUI && playerSettings.getBool(player.getUniqueId(), "chat.user_messages")) {
            if (isOverride)
                player.sendMessage(PREFIX.append(
                        Component.text("Using: " + workDir.getName(), NamedTextColor.GRAY)));
            player.sendMessage(Component.text("─────────────────────────────────────",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("▶ You  ", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(message, NamedTextColor.WHITE)));
        }

        File logFile = openLogFile(player.getName());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PrintWriter log = new PrintWriter(new FileWriter(logFile, true))) {
                LocalDateTime start = LocalDateTime.now();
                log.printf("=== Claude Session ===%n");
                log.printf("Player:  %s%n", player.getName());
                log.printf("Project: %s%n", workDir.getAbsolutePath());
                log.printf("Started: %s%n%n", start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                log.printf("> %s%n%n", message);
                log.flush();

                // Buffer for Claude text lines shown in the compact card
                List<Component> bufferedClaudeLines = new ArrayList<>();

                // Terminal history events (always, if terminal was ever opened)
                Consumer<TerminalEvent> terminalEvents = terminalRef == null ? null :
                        event -> plugin.getServer().getScheduler().runTask(plugin,
                                () -> terminalRef.onEvent(event));

                // Chat display events (only when terminal is not showing)
                Consumer<TerminalEvent> chatEvents = useTerminalUI ? null :
                        event -> plugin.getServer().getScheduler().runTask(plugin,
                                () -> handleChatEvent(event, player, bufferedClaudeLines));

                // Mod overlay events — always relay (client silently ignores if mod not installed)
                Consumer<TerminalEvent> modEvents = event -> {
                    String json = toModJson(event);
                    if (json != null) plugin.getServer().getScheduler().runTask(plugin,
                            () -> sendModEvent(player, json));
                };

                // Fan out to all active consumers
                List<Consumer<TerminalEvent>> allConsumers = new ArrayList<>();
                allConsumers.add(modEvents);
                if (terminalEvents != null) allConsumers.add(terminalEvents);
                if (chatEvents != null) allConsumers.add(chatEvents);
                Consumer<TerminalEvent> allEvents = event -> allConsumers.forEach(c -> c.accept(event));

                session.send(message, component -> {
                    String plain = PlainTextComponentSerializer.plainText().serialize(component);
                    log.println(plain);
                    log.flush();
                    // Display is handled via structured TerminalEvent callbacks
                }, allEvents);

                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.getPlayerData().setHasSession(player.getUniqueId(), workDir.getAbsolutePath(), true));
                log.printf("%nEnded: %s%n%n",
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (Exception e) {
                plugin.getLogger().severe("Claude error: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.sendMessage(PREFIX.append(
                            Component.text(e.getMessage(), NamedTextColor.RED))));
            }
        });
    }

    private void scaffoldNewProject(File dir, String projectName, String packageToken, String className) throws Exception {
        File self = plugin.getSelfDir();
        dir.mkdirs();
        copyFile(new File(self, "gradlew.bat"), new File(dir, "gradlew.bat"));
        copyFile(new File(self, "gradlew"), new File(dir, "gradlew"));
        File wrapperDir = new File(dir, "gradle/wrapper");
        wrapperDir.mkdirs();
        copyFile(new File(self, "gradle/wrapper/gradle-wrapper.jar"),
                new File(wrapperDir, "gradle-wrapper.jar"));
        copyFile(new File(self, "gradle/wrapper/gradle-wrapper.properties"),
                new File(wrapperDir, "gradle-wrapper.properties"));

        // Minimal build files
        writeText(new File(dir, "settings.gradle.kts"), "rootProject.name = \"" + projectName + "\"\n");
        writeText(new File(dir, "build.gradle.kts"),
                "plugins { java }\n" +
                "group = \"com.example\"\n" +
                "version = \"1.0-SNAPSHOT\"\n" +
                "repositories {\n" +
                "    mavenCentral()\n" +
                "    maven(\"https://repo.papermc.io/repository/maven-public/\")\n" +
                "}\n" +
                "dependencies {\n" +
                "    compileOnly(\"io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT\")\n" +
                "}\n" +
                "java { toolchain.languageVersion = JavaLanguageVersion.of(21) }\n");

        String pkg = "com.example." + packageToken;
        String pkgPath = pkg.replace('.', '/');
        File srcDir = new File(dir, "src/main/java/" + pkgPath);
        srcDir.mkdirs();
        new File(dir, "src/main/resources").mkdirs();

        writeText(new File(srcDir, className + ".java"),
                "package " + pkg + ";\n\n" +
                "import org.bukkit.plugin.java.JavaPlugin;\n\n" +
            "public class " + className + " extends JavaPlugin {\n" +
            "    @Override public void onEnable() { getLogger().info(\"" + projectName + " enabled!\"); }\n" +
            "    @Override public void onDisable() { getLogger().info(\"" + projectName + " disabled!\"); }\n" +
                "}\n");

        writeText(new File(dir, "src/main/resources/plugin.yml"),
            "name: " + projectName + "\n" +
                "version: '1.0-SNAPSHOT'\n" +
            "main: " + pkg + "." + className + "\n" +
                "api-version: '1.21'\n");

        writeText(new File(dir, ".gitignore"), ".gradle/\nbuild/\n!gradle/wrapper/gradle-wrapper.jar\n");

        // ECS skeleton
        String ecsPkg = pkg + ".ecs";
        String compPkg = pkg + ".component";
        String sysPkg = pkg + ".system";
        File ecsDir  = new File(dir, "src/main/java/" + ecsPkg.replace('.', '/'));
        File compDir = new File(dir, "src/main/java/" + compPkg.replace('.', '/'));
        File sysDir  = new File(dir, "src/main/java/" + sysPkg.replace('.', '/'));
        ecsDir.mkdirs(); compDir.mkdirs(); sysDir.mkdirs();

        writeText(new File(ecsDir, "EntityManager.java"),
                "package " + ecsPkg + ";\n\n" +
                "import java.util.*;\n\n" +
                "/** Attaches and retrieves components keyed by entity UUID. */\n" +
                "public class EntityManager {\n" +
                "    private final Map<UUID, Map<Class<?>, Object>> store = new HashMap<>();\n\n" +
                "    public <T> void add(UUID id, T component) {\n" +
                "        store.computeIfAbsent(id, k -> new HashMap<>()).put(component.getClass(), component);\n" +
                "    }\n\n" +
                "    @SuppressWarnings(\"unchecked\")\n" +
                "    public <T> Optional<T> get(UUID id, Class<T> type) {\n" +
                "        Map<Class<?>, Object> m = store.get(id);\n" +
                "        return m == null ? Optional.empty() : Optional.ofNullable(type.cast(m.get(type)));\n" +
                "    }\n\n" +
                "    public void remove(UUID id) { store.remove(id); }\n" +
                "    public Set<UUID> all() { return Collections.unmodifiableSet(store.keySet()); }\n" +
                "}\n");

        writeText(new File(ecsDir, "ArchetypeLoader.java"),
                "package " + ecsPkg + ";\n\n" +
                "import org.bukkit.configuration.ConfigurationSection;\n" +
                "import org.bukkit.configuration.file.YamlConfiguration;\n" +
                "import org.bukkit.plugin.java.JavaPlugin;\n\n" +
                "import java.io.File;\n" +
                "import java.util.*;\n\n" +
                "/** Loads YAML archetypes from src/main/resources/data/ at plugin startup. */\n" +
                "public class ArchetypeLoader {\n" +
                "    private final Map<String, ConfigurationSection> archetypes = new HashMap<>();\n\n" +
                "    public void load(JavaPlugin plugin, String folder) {\n" +
                "        File dir = new File(plugin.getDataFolder(), folder);\n" +
                "        if (!dir.exists()) { plugin.saveResource(folder + \"/.keep\", false); return; }\n" +
                "        for (File f : Objects.requireNonNull(dir.listFiles((d, n) -> n.endsWith(\".yml\")))) {\n" +
                "            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);\n" +
                "            for (String key : cfg.getKeys(false)) {\n" +
                "                archetypes.put(key, cfg.getConfigurationSection(key));\n" +
                "            }\n" +
                "        }\n" +
                "        plugin.getLogger().info(\"Loaded \" + archetypes.size() + \" archetypes from \" + folder);\n" +
                "    }\n\n" +
                "    public Optional<ConfigurationSection> get(String key) {\n" +
                "        return Optional.ofNullable(archetypes.get(key));\n" +
                "    }\n" +
                "    public Map<String, ConfigurationSection> all() { return Collections.unmodifiableMap(archetypes); }\n" +
                "}\n");

        // Example component
        writeText(new File(compDir, "ExampleComponent.java"),
                "package " + compPkg + ";\n\n" +
                "/** Example component — replace with real data fields. */\n" +
                "public record ExampleComponent(String archetypeKey, double value) {}\n");

        // Example system
        writeText(new File(sysDir, "ExampleSystem.java"),
                "package " + sysPkg + ";\n\n" +
                "import " + ecsPkg + ".EntityManager;\n" +
                "import " + compPkg + ".ExampleComponent;\n" +
                "import org.bukkit.Bukkit;\n\n" +
                "/** Example system — processes ExampleComponent each tick. */\n" +
                "public class ExampleSystem {\n" +
                "    public void tick(EntityManager em) {\n" +
                "        for (var id : em.all()) {\n" +
                "            em.get(id, ExampleComponent.class).ifPresent(c -> {\n" +
                "                // TODO: implement behaviour using c.archetypeKey() / c.value()\n" +
                "            });\n" +
                "        }\n" +
                "    }\n" +
                "}\n");

        // data/ archetype directory + example YAML
        File dataDir = new File(dir, "src/main/resources/data");
        dataDir.mkdirs();
        writeText(new File(dataDir, "example_archetypes.yml"),
            "# Archetypes for " + projectName + "\n" +
                "# Each top-level key is an archetype ID referenced from Java via ArchetypeLoader.\n" +
                "# Edit this file to add or tune content without touching Java code.\n\n" +
                "example_basic:\n" +
                "  value: 10.0\n" +
                "  description: \"A basic example archetype\"\n\n" +
                "example_strong:\n" +
                "  value: 25.0\n" +
                "  description: \"A stronger variant\"\n");
    }

    // -------------------------------------------------------------------------
    // Mod overlay support

    /** Called by ModInputListener when the player submits via the mod input overlay. */
    public void submitFromMod(Player player, String message) {
        String provider = plugin.getAiProvider();
        terminals.computeIfAbsent(player.getUniqueId(),
            id -> new ClaudeTerminalUI(player, plugin,
                new SessionHistory(SessionHistory.fileFor(plugin.getDataFolder(), id, provider)),
                provider));
        String saved = plugin.getPlayerData().getDefaultPath(player.getUniqueId());
        if (saved == null) {
            String globalDefault = plugin.getDefaultRepo();
            if (globalDefault != null && !globalDefault.isBlank()) {
                saved = globalDefault;
                plugin.getPlayerData().setDefaultPath(player.getUniqueId(), saved);
            } else {
                pendingMessages.put(player.getUniqueId(), message);
                showOnboarding(player);
                return;
            }
        }
        File workDir = new File(saved);
        if (MOD_MOCK_TRIGGER.equals(message)) {
            runMockClaude(player, workDir, "mock stream test from backtick shortcut", false);
            return;
        }
        runClaude(player, workDir, message, false);
    }

    private void sendModEvent(Player player, String json) {
        try {
            if (trySendModJson(player, json)) return;
            if (trySendOversizeEvent(player, json)) return;

            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            String type = "unknown";
            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("type")) type = obj.get("type").getAsString();
            } catch (Exception ignored) {}
            plugin.getLogger().warning("Dropping oversize vibecraft:events payload type=" + type + " (" + bytes + " bytes)");
        } catch (Exception ignored) {}
    }

    private boolean trySendModJson(Player player, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PLUGIN_MESSAGE_BYTES) return false;
        player.sendPluginMessage(plugin, "vibecraft:events", payload);
        return true;
    }

    private boolean trySendOversizeEvent(Player player, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("type")) return false;

            String type = obj.get("type").getAsString();
            if ("claude_text".equals(type) || "bash_output".equals(type)) {
                if (!obj.has("lines") || !obj.get("lines").isJsonArray()) return false;
                JsonArray lines = obj.getAsJsonArray("lines");
                boolean sent = false;
                for (var el : lines) {
                    String line = el.isJsonNull() ? "" : el.getAsString();
                    sent |= sendSingleLineEvent(player, type, line);
                }
                return sent;
            }

            if ("thinking".equals(type)) {
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                if (text.isEmpty()) return sendSingleThinkingEvent(player, "");

                boolean sent = false;
                for (String part : text.split("\\n", -1)) {
                    sent |= sendSingleThinkingEvent(player, part);
                }
                return sent;
            }

            if ("tool_call".equals(type)) {
                String tool = obj.has("tool") ? obj.get("tool").getAsString() : "";
                String detail = obj.has("detail") ? obj.get("detail").getAsString() : "";
                String fitted = fitTextToPayloadBudget(detail, txt -> {
                    JsonObject evt = new JsonObject();
                    evt.addProperty("type", "tool_call");
                    evt.addProperty("tool", tool);
                    evt.addProperty("detail", txt);
                    return evt;
                });

                JsonObject evt = new JsonObject();
                evt.addProperty("type", "tool_call");
                evt.addProperty("tool", tool);
                evt.addProperty("detail", fitted);
                return trySendModJson(player, evt.toString());
            }

            if ("user_message".equals(type)) {
                String text = obj.has("text") ? obj.get("text").getAsString() : "";
                String fitted = fitTextToPayloadBudget(text, txt -> {
                    JsonObject evt = new JsonObject();
                    evt.addProperty("type", "user_message");
                    evt.addProperty("text", txt);
                    return evt;
                });

                JsonObject evt = new JsonObject();
                evt.addProperty("type", "user_message");
                evt.addProperty("text", fitted);
                return trySendModJson(player, evt.toString());
            }

            if ("question".equals(type)) {
                String prompt = obj.has("prompt") ? obj.get("prompt").getAsString() : "";
                JsonArray options = obj.has("options") && obj.get("options").isJsonArray()
                        ? obj.getAsJsonArray("options") : new JsonArray();

                // First drop trailing options until event fits; if still too large, trim prompt.
                JsonArray trimmedOptions = new JsonArray();
                for (var el : options) trimmedOptions.add(el);

                while (trimmedOptions.size() > 0) {
                    JsonObject evt = new JsonObject();
                    evt.addProperty("type", "question");
                    evt.addProperty("prompt", prompt);
                    evt.add("options", trimmedOptions);
                    if (eventByteSize(evt) <= MAX_PLUGIN_MESSAGE_BYTES) {
                        return trySendModJson(player, evt.toString());
                    }
                    trimmedOptions.remove(trimmedOptions.size() - 1);
                }

                String fittedPrompt = fitTextToPayloadBudget(prompt, txt -> {
                    JsonObject evt = new JsonObject();
                    evt.addProperty("type", "question");
                    evt.addProperty("prompt", txt);
                    evt.add("options", new JsonArray());
                    return evt;
                });

                JsonObject evt = new JsonObject();
                evt.addProperty("type", "question");
                evt.addProperty("prompt", fittedPrompt);
                evt.add("options", new JsonArray());
                return trySendModJson(player, evt.toString());
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean sendSingleLineEvent(Player player, String type, String line) {
        String fitted = fitTextToPayloadBudget(line, txt -> {
            JsonObject evt = new JsonObject();
            evt.addProperty("type", type);
            JsonArray arr = new JsonArray();
            arr.add(txt);
            evt.add("lines", arr);
            return evt;
        });

        JsonObject evt = new JsonObject();
        evt.addProperty("type", type);
        JsonArray arr = new JsonArray();
        arr.add(fitted);
        evt.add("lines", arr);
        return trySendModJson(player, evt.toString());
    }

    private boolean sendSingleThinkingEvent(Player player, String text) {
        String fitted = fitTextToPayloadBudget(text, txt -> {
            JsonObject evt = new JsonObject();
            evt.addProperty("type", "thinking");
            evt.addProperty("text", txt);
            return evt;
        });

        JsonObject evt = new JsonObject();
        evt.addProperty("type", "thinking");
        evt.addProperty("text", fitted);
        return trySendModJson(player, evt.toString());
    }

    private String fitTextToPayloadBudget(String text, Function<String, JsonObject> eventBuilder) {
        if (text == null) return "";
        if (eventByteSize(eventBuilder.apply(text)) <= MAX_PLUGIN_MESSAGE_BYTES) return text;

        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String candidate = text.substring(0, mid) + "...";
            if (eventByteSize(eventBuilder.apply(candidate)) <= MAX_PLUGIN_MESSAGE_BYTES) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        if (lo <= 0) return "";
        return text.substring(0, lo) + "...";
    }

    private static int eventByteSize(JsonObject obj) {
        return obj.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** Called by ModInputListener when the mod requests the persisted session history. */
    public void sendHistoryAndSettingsToMod(Player player) {
        sendUiSchemaToMod(player);
        sendHistoryToMod(player);
        sendSettingsToMod(player);
    }

    /** Public hook for other plugins that need to refresh mod-side UI schema. */
    public void pushUiSchemaToMod(Player player) {
        sendUiSchemaToMod(player);
    }

    private void sendUiSchemaToMod(Player player) {
        try {
            JsonObject schema = plugin.getUiRegistry().buildMergedSchema(plugin.getDataFolder());
            JsonObject evt = new JsonObject();
            evt.addProperty("type", "ui_schema");
            evt.add("schema", schema);
            sendModEvent(player, evt.toString());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send UI schema: " + e.getMessage());
        }
    }

    public void sendSettingsToMod(Player player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "settings");
        JsonObject vals = new JsonObject();
        for (Map.Entry<String, String> e : playerSettings.getAll(player.getUniqueId()).entrySet()) {
            vals.addProperty(e.getKey(), e.getValue());
        }
        obj.add("values", vals);
        sendModEvent(player, obj.toString());
    }

    public void applySetting(Player player, String key, String value) {
        playerSettings.set(player.getUniqueId(), key, value);
    }

    public void clearHistoryFor(Player player) {
        String provider = plugin.getAiProvider();
        SessionHistory history = new SessionHistory(
            SessionHistory.fileFor(plugin.getDataFolder(), player.getUniqueId(), provider));
        history.clear();

        ClaudeTerminalUI terminal = terminals.get(player.getUniqueId());
        if (terminal != null) terminal.clearHistory();

        sendHistoryToMod(player);
    }

    private void sendHistoryToMod(Player player) {
        // Prefer in-memory entries from the live terminal (includes thinking) over the file.
        String provider = plugin.getAiProvider();
        ClaudeTerminalUI terminal = terminals.get(player.getUniqueId());
        List<SessionHistory.Entry> entries = terminal != null
            ? terminal.getEntries()
            : new SessionHistory(SessionHistory.fileFor(plugin.getDataFolder(), player.getUniqueId(), provider)).entries();

        // Backward compatibility for older mod clients that only understand "history".
        sendLegacyHistory(player, entries);

        JsonArray chunk = new JsonArray();
        for (SessionHistory.Entry e : entries) {
            JsonObject je = new JsonObject();
            je.addProperty("type", e.type().name());
            je.addProperty("header", e.header());
            je.addProperty("body", truncateForTransport(e.type(), e.body()));

            chunk.add(je);
            if (chunkPayloadSize(chunk, false) > HISTORY_CHUNK_TARGET_BYTES) {
                chunk.remove(chunk.size() - 1);
                if (chunk.size() > 0) {
                    sendHistoryChunk(player, chunk, false);
                    chunk = new JsonArray();
                }
                chunk.add(je);
            }
        }
        sendHistoryChunk(player, chunk, true);
    }

    private void sendLegacyHistory(Player player, List<SessionHistory.Entry> entries) {
        JsonArray arr = new JsonArray();
        for (SessionHistory.Entry e : entries) {
            JsonObject je = new JsonObject();
            je.addProperty("type", e.type().name());
            je.addProperty("header", e.header());
            je.addProperty("body", truncateForTransport(e.type(), e.body()));

            arr.add(je);
            trimLegacyHistoryToBudget(arr, LEGACY_HISTORY_MAX_BYTES);
        }

        // Final hard cap at transport limit so sendModEvent never sees oversize legacy history.
        trimLegacyHistoryToBudget(arr, MAX_PLUGIN_MESSAGE_BYTES);

        JsonObject obj = new JsonObject();
        obj.addProperty("type", "history");
        obj.add("entries", arr);
        sendModEvent(player, obj.toString());
    }

    private static void trimLegacyHistoryToBudget(JsonArray arr, int budgetBytes) {
        while (arr.size() > 0) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "history");
            obj.add("entries", arr);
            int size = obj.toString().getBytes(StandardCharsets.UTF_8).length;
            if (size <= budgetBytes) break;
            arr.remove(0);
        }
    }

    private void sendHistoryChunk(Player player, JsonArray entries, boolean done) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "history_chunk");
        obj.addProperty("done", done);
        obj.add("entries", entries);
        sendModEvent(player, obj.toString());
    }

    private int chunkPayloadSize(JsonArray entries, boolean done) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "history_chunk");
        obj.addProperty("done", done);
        obj.add("entries", entries);
        return obj.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static String truncateForTransport(SessionHistory.Type type, String body) {
        if (type != SessionHistory.Type.TOOL) return body == null ? "" : body;
        final int maxChars = 8000;
        if (body == null) return "";
        if (body.length() <= maxChars) return body;
        return body.substring(0, maxChars) + "…";
    }

    private boolean isWithinWorkspace(File candidate) {
        try {
            File workspace = plugin.getWorkspaceDir().getCanonicalFile();
            File target = candidate.getCanonicalFile();
            String wsPath = workspace.getPath();
            String targetPath = target.getPath();
            return targetPath.equals(wsPath) || targetPath.startsWith(wsPath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static String sanitizePackageToken(String name) {
        String token = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (token.isEmpty() || !Character.isLetter(token.charAt(0))) token = "plugin_" + token;
        return token;
    }

    private static String sanitizeClassName(String name) {
        String[] parts = name.split("[^A-Za-z0-9]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        if (out.length() == 0) out.append("PluginMain");
        if (!Character.isJavaIdentifierStart(out.charAt(0))) out.insert(0, 'P');
        for (int i = 1; i < out.length(); i++) {
            if (!Character.isJavaIdentifierPart(out.charAt(i))) out.setCharAt(i, '_');
        }
        return out.toString();
    }

    private static String toModJson(TerminalEvent event) {
        return switch (event) {
            case TerminalEvent.StreamStart e -> "{\"type\":\"stream_start\"}";
            case TerminalEvent.StreamEnd   e -> "{\"type\":\"stream_end\"}";
            case TerminalEvent.ToolCall e -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "tool_call");
                obj.addProperty("tool", e.toolName());
                obj.addProperty("detail", e.detail());
                yield obj.toString();
            }
            case TerminalEvent.ClaudeText e -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "claude_text");
                JsonArray arr = new JsonArray();
                for (Component c : e.lines())
                    arr.add(LegacyComponentSerializer.legacySection().serialize(c));
                obj.add("lines", arr);
                yield obj.toString();
            }
            case TerminalEvent.BashOutput e -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "bash_output");
                JsonArray arr = new JsonArray();
                for (String l : e.lines()) arr.add(l);
                obj.add("lines", arr);
                yield obj.toString();
            }
            case TerminalEvent.Thinking e -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "thinking");
                obj.addProperty("text", e.text());
                yield obj.toString();
            }
            case TerminalEvent.Question e -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("type", "question");
                obj.addProperty("prompt", e.prompt());
                JsonArray arr = new JsonArray();
                for (String opt : e.options()) arr.add(opt);
                obj.add("options", arr);
                yield obj.toString();
            }
        };
    }

    // -------------------------------------------------------------------------
    // Chat-mode display helpers

    private void handleChatEvent(TerminalEvent event, Player player, List<Component> buffer) {
        UUID uuid = player.getUniqueId();
        switch (event) {
            case TerminalEvent.StreamStart e ->
                player.sendActionBar(Component.text("⏳ Claude is processing…", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            case TerminalEvent.ToolCall e -> {
                player.sendActionBar(buildActionBarForTool(e.toolName(), e.detail()));
                if (playerSettings.getBool(uuid, "chat.tools")) {
                    player.sendMessage(PREFIX.append(
                            Component.text("[" + e.toolName() + "] " + e.detail(), NamedTextColor.GOLD)
                                    .decoration(TextDecoration.ITALIC, false)));
                }
            }
            case TerminalEvent.BashOutput e -> {
                if (playerSettings.getBool(uuid, "chat.bash")) {
                    for (String l : e.lines())
                        player.sendMessage(Component.text(l, NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false));
                }
            }
            case TerminalEvent.Thinking e -> {
                if (playerSettings.getBool(uuid, "chat.thinking")) {
                    String preview = e.text().split("\n")[0];
                    if (preview.length() > 120) preview = preview.substring(0, 120) + "…";
                    player.sendMessage(PREFIX.append(
                            Component.text("💭 " + preview, NamedTextColor.GRAY)
                                    .decoration(TextDecoration.ITALIC, false)));
                }
            }
            case TerminalEvent.ClaudeText e -> {
                if (playerSettings.getBool(uuid, "chat.claude_text"))
                    buffer.addAll(e.lines());
            }
            case TerminalEvent.StreamEnd e -> {
                player.sendActionBar(Component.empty());
                if (anyChatEnabled(player.getUniqueId())) {
                    if (!buffer.isEmpty()) sendChatResponseCard(player, buffer);
                    sendChatQuickActions(player);
                }
            }
            default -> {}
        }
    }

    private Component buildActionBarForTool(String toolName, String detail) {
        // TODO: consolidate — duplicated tool-type switch exists in ClaudeSession, ClaudeCommand, and ClaudeTerminalUI; extract shared logic
        NamedTextColor color = switch (toolName) {
            case "Write"               -> NamedTextColor.GREEN;
            case "Edit"                -> NamedTextColor.YELLOW;
            case "Bash", "PowerShell"  -> NamedTextColor.GOLD;
            default                    -> NamedTextColor.GRAY;
        };
        Component bar = Component.text("⏳ [" + toolName + "]  ", color)
                .decoration(TextDecoration.ITALIC, false);
        if (!detail.isEmpty()) {
            bar = bar.append(Component.text(detail, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return bar;
    }

    private void sendChatResponseCard(Player player, List<Component> lines) {
        Component tag = Component.text("◆ ", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false);
        int shown = Math.min(CARD_PREVIEW_LINES, lines.size());
        for (int i = 0; i < shown; i++) {
            player.sendMessage(tag.append(lines.get(i).decoration(TextDecoration.ITALIC, false)));
        }
        if (lines.size() > CARD_PREVIEW_LINES) {
            lastResponseBook.put(player.getUniqueId(), new ArrayList<>(lines));
            int extra = lines.size() - CARD_PREVIEW_LINES;
            player.sendMessage(Component.text("  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("[▶ " + extra + " more line" + (extra == 1 ? "" : "s") + "]",
                                    NamedTextColor.YELLOW)
                            .decorate(TextDecoration.UNDERLINED)
                            .decoration(TextDecoration.ITALIC, false)
                            .clickEvent(ClickEvent.runCommand("/claude book"))
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("Click to read full response", NamedTextColor.GRAY)
                                            .decoration(TextDecoration.ITALIC, false)))));
        }
    }

    private boolean anyChatEnabled(UUID uuid) {
        return playerSettings.getBool(uuid, "chat.user_messages")
            || playerSettings.getBool(uuid, "chat.claude_text")
            || playerSettings.getBool(uuid, "chat.tools")
            || playerSettings.getBool(uuid, "chat.bash")
            || playerSettings.getBool(uuid, "chat.thinking");
    }

    private void sendChatQuickActions(Player player) {
        player.sendMessage(
                Component.text("  ", NamedTextColor.DARK_GRAY)
                        .append(Component.text("[ ↩ Reply ]", NamedTextColor.GREEN)
                                .decoration(TextDecoration.BOLD, true)
                                .decoration(TextDecoration.ITALIC, false)
                                .clickEvent(ClickEvent.suggestCommand("/claude "))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Type a follow-up", NamedTextColor.GRAY)
                                                .decoration(TextDecoration.ITALIC, false))))
                        .append(Component.text("   ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("[ ⌂ Terminal ]", NamedTextColor.AQUA)
                                .decoration(TextDecoration.BOLD, true)
                                .decoration(TextDecoration.ITALIC, false)
                                .clickEvent(ClickEvent.runCommand("/claude"))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Open terminal UI", NamedTextColor.GRAY)
                                                .decoration(TextDecoration.ITALIC, false))))
                        .append(Component.text("   ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("[ ↺ Reset ]", NamedTextColor.RED)
                                .decoration(TextDecoration.BOLD, true)
                                .decoration(TextDecoration.ITALIC, false)
                                .clickEvent(ClickEvent.runCommand("/claude reset"))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Clear session", NamedTextColor.GRAY)
                                                .decoration(TextDecoration.ITALIC, false))))
        );
    }

    private void openResponseBook(Player player, List<Component> lines) {
        final int LINES_PER_PAGE = 13;
        List<Component> pages = new ArrayList<>();
        int start = 0;
        while (start < lines.size()) {
            Component page = Component.empty();
            int end = Math.min(start + LINES_PER_PAGE, lines.size());
            for (int i = start; i < end; i++) {
                if (i > start) page = page.append(Component.text("\n"));
                page = page.append(lines.get(i).decoration(TextDecoration.ITALIC, false));
            }
            pages.add(page);
            start += LINES_PER_PAGE;
        }
        if (pages.isEmpty()) return;
        player.openBook(Book.book(
                Component.text("Claude Response"),
                Component.text("Claude"),
                pages));
    }

    private void sendUsage(Player player) {
        player.sendMessage(PREFIX.append(Component.text(
                "/claude <message>  |  /claude --path <dir> <message>  |  " +
                "/claude mock [--path <dir>] <message>  |  " +
                "/claude repo [set <path>|new <name>|show]  |  /claude reset",
                NamedTextColor.GRAY)));
    }

    private File openLogFile(String playerName) {
        File dir = new File(plugin.getDataFolder(), "sessions");
        dir.mkdirs();
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return new File(dir, playerName + "-" + date + ".log");
    }

    private static void copyFile(File src, File dst) throws Exception {
        if (!src.exists()) return;
        java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeText(File f, String text) throws Exception {
        java.nio.file.Files.writeString(f.toPath(), text);
    }
}

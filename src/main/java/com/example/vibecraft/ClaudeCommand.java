package com.example.vibecraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ClaudeCommand implements CommandExecutor {

    private static final Component PREFIX = Component.text("[Claude] ", NamedTextColor.AQUA);

    private final VibeCraft plugin;
    private final Map<String, ClaudeSession> sessions = new HashMap<>();
    // Stores a pending message for a player who triggered onboarding mid-command.
    private final Map<UUID, String> pendingMessages = new HashMap<>();

    public ClaudeCommand(VibeCraft plugin) {
        this.plugin = plugin;
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
            sendUsage(player);
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

        // Parse optional --path <dir> flag (anywhere in args)
        File overridePath = null;
        List<String> messageTokens = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
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
                pendingMessages.put(player.getUniqueId(), message);
                showOnboarding(player);
                return true;
            }
            workDir = new File(saved);
        }

        runClaude(player, workDir, message, overridePath != null);
        return true;
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
            String name = args[2];
            File workspace = plugin.getWorkspaceDir();
            File newProject = new File(workspace, name);
            if (newProject.exists()) {
                player.sendMessage(PREFIX.append(
                        Component.text("Directory already exists: " + newProject.getAbsolutePath(), NamedTextColor.RED)));
                return true;
            }
            player.sendMessage(PREFIX.append(
                    Component.text("Creating new plugin project '" + name + "'...", NamedTextColor.GRAY)));
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    // Scaffold a new plugin project by copying VibeCraft's gradle wrapper + build files
                    scaffoldNewProject(newProject, name);
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
            return new ClaudeSession(workDir, plugin.getClaudePath(),
                    plugin.getServerPluginsDir(), plugin.getRestartFlagPath(),
                    plugin.getBuildScripts().getServerDir(),
                    plugin.getPlayerData().getAllConfiguredPaths(),
                    saved);
        });

        if (isOverride) {
            player.sendMessage(PREFIX.append(
                    Component.text("Using: " + workDir.getName(), NamedTextColor.GRAY)));
        }
        player.sendMessage(PREFIX.append(Component.text("Thinking...", NamedTextColor.GRAY)));

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

                session.send(message, component -> {
                    String plain = PlainTextComponentSerializer.plainText().serialize(component);
                    log.println(plain);
                    log.flush();
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(component));
                });

                plugin.getPlayerData()
                        .setHasSession(player.getUniqueId(), workDir.getAbsolutePath(), true);
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

    private void scaffoldNewProject(File dir, String name) throws Exception {
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
        writeText(new File(dir, "settings.gradle.kts"), "rootProject.name = \"" + name + "\"\n");
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

        String pkg = "com.example." + name.toLowerCase();
        String pkgPath = pkg.replace('.', '/');
        File srcDir = new File(dir, "src/main/java/" + pkgPath);
        srcDir.mkdirs();
        new File(dir, "src/main/resources").mkdirs();

        writeText(new File(srcDir, name + ".java"),
                "package " + pkg + ";\n\n" +
                "import org.bukkit.plugin.java.JavaPlugin;\n\n" +
                "public class " + name + " extends JavaPlugin {\n" +
                "    @Override public void onEnable() { getLogger().info(\"" + name + " enabled!\"); }\n" +
                "    @Override public void onDisable() { getLogger().info(\"" + name + " disabled!\"); }\n" +
                "}\n");

        writeText(new File(dir, "src/main/resources/plugin.yml"),
                "name: " + name + "\n" +
                "version: '1.0-SNAPSHOT'\n" +
                "main: " + pkg + "." + name + "\n" +
                "api-version: '1.21'\n");

        writeText(new File(dir, ".gitignore"), ".gradle/\nbuild/\n!gradle/wrapper/gradle-wrapper.jar\n");
    }

    private void sendUsage(Player player) {
        player.sendMessage(PREFIX.append(Component.text(
                "/claude <message>  |  /claude --path <dir> <message>  |  " +
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

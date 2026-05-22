package com.example.vibecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public class ClaudeSession {
    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_LINE = 200;

    private final File workingDir;
    private final String claudePath;
    private final String hermesPath;
    private final String aiProvider;
    private final String serverPluginsDir;
    private final String restartFlagPath;
    private final File serverDir;
    private final List<String> allPluginPaths;
    private boolean hasSession;

    // Maps tool_use_id -> tool_name so tool_result events know what produced them
    private final Map<String, String> pendingToolNames = new HashMap<>();

    /** When Claude calls AskUserQuestion, the CLI auto-dismisses it and Claude often
     *  follows with a "the prompt was dismissed" filler text. This flag suppresses
     *  the next assistant text block so that filler doesn't leak into chat. */
    private boolean suppressNextText = false;

    /** Tracks cumulative thinking text for a single send() call so we can stream deltas. */
    private String lastThinkingText = "";

    public ClaudeSession(File workingDir, String claudePath, String hermesPath, String aiProvider,
                         String serverPluginsDir, String restartFlagPath,
                         File serverDir, List<String> allPluginPaths,
                         boolean hasSession) {
        this.workingDir = workingDir;
        this.claudePath = claudePath;
        this.hermesPath = hermesPath;
        this.aiProvider = aiProvider;
        this.serverPluginsDir = serverPluginsDir;
        this.restartFlagPath = restartFlagPath;
        this.serverDir = serverDir;
        this.allPluginPaths = allPluginPaths;
        this.hasSession = hasSession;
    }

    public File getWorkingDir() { return workingDir; }
    public boolean isHasSession() { return hasSession; }

    private String buildSystemPrompt() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String sep = isWindows ? "\\" : "/";
        String gradleCmd = isWindows ? "cmd /c gradlew.bat build" : "./gradlew build";
        String buildScriptExt = isWindows ? ".bat" : ".sh";
        String buildScriptPrefix = isWindows ? "cmd /c \"" : "bash \"";
        String mcScript = isWindows
                ? "powershell -ExecutionPolicy Bypass -File \"" + serverDir.getAbsolutePath() + "\\mc.ps1\""
                : "bash \"" + serverDir.getAbsolutePath() + "/mc.sh\"";

        // TODO: move system prompt to a resource template file — large string literals in Java are hard to maintain
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Minecraft Paper plugin developer assistant. ");
        sb.append("Working directory: ").append(workingDir.getAbsolutePath()).append(". ");

        sb.append("AUTONOMY — execute, don't ask: whenever a task requires a build, deploy, reload, or restart, ");
        sb.append("do it yourself using the tools below. Never tell the user to run a command or restart the server — ");
        sb.append("just do it. The user's job is to describe what they want; your job is to make it happen end-to-end. ");

        sb.append("COMMAND ACCESS you may use at any time without confirmation: ");
        sb.append("(1) Edit any source file in any registered plugin. ");
        sb.append("(2) Build and deploy THIS plugin: run `").append(gradleCmd)
          .append("` in the working directory, then copy the jar from build").append(sep).append("libs").append(sep)
          .append(" to ").append(serverPluginsDir).append(". ");
        sb.append("(3) Build and deploy ANY OTHER registered plugin: run its dedicated build script using ")
          .append(buildScriptPrefix).append("<serverDir>").append(sep).append("build-<Name>").append(buildScriptExt).append("\" . ");
        sb.append("(4) Reload after any change — use the PLUGIN-SPECIFIC command, never the global /reload: ");
        sb.append("EnchantForge changes → ").append(mcScript).append(" \"cenchant reload\". ");
        sb.append("VibeCraft changes → create the restart flag (see below). ");
        sb.append("NEVER run ").append(mcScript).append(" \"reload confirm\" — ");
        sb.append("that reloads ALL plugins including VibeCraft itself, which kills your own running session mid-process. ");
        sb.append("(5) Restart the server (for VibeCraft Java changes): create the file ").append(restartFlagPath)
          .append(" — the server detects this and restarts automatically. ");
        sb.append("Workflow for EnchantForge changes: edit source → build → deploy → ").append(mcScript).append(" \"cenchant reload\". ");
        sb.append("Workflow for VibeCraft changes: edit source → build → deploy → create restart flag. ");

        sb.append("TWO-REPO RULE — strictly separate data from code: ");
        sb.append("(1) YAML DATA FILES are authored in src/main/resources/ in the source repo. ");
        sb.append("The build script copies them to ").append(serverPluginsDir).append("<PluginName>/ on every deploy, ");
        sb.append("so the SOURCE copy is always authoritative. Edit src/main/resources/ for any content change; ");
        sb.append("then run the build script and reload. Do NOT edit the server copy directly — it will be overwritten on next build. ");
        sb.append("(2) JAVA SOURCE CODE belongs in the source repo. ");
        sb.append("Only touch Java when the YAML schema needs a new field, a new trigger type, a new system, ");
        sb.append("or genuinely new game mechanics. After any Java change: build, deploy, restart. ");
        sb.append("DEFAULT DECISION: if the user asks to add or change content, reach for src/main/resources/ YAML first. ");
        sb.append("Only open Java if the request cannot be satisfied by editing YAML alone. ");

        sb.append("ARCHITECTURE GUIDELINES — follow these by default on every plugin: ");
        sb.append("(A) YAML-FIRST DATA DESIGN: all game content (mobs, items, abilities, quests, structures, enchants, etc.) ");
        sb.append("lives as YAML archetypes in the server data dir (see TWO-REPO RULE above). ");
        sb.append("Java code reads these files at runtime; never hardcode stats, loot tables, or behaviour parameters in Java. ");
        sb.append("(B) ENTITY COMPONENT SYSTEM (ECS): design plugins with three layers: ");
        sb.append("Components (plain data POJOs or records — no logic), ");
        sb.append("Systems (stateless classes that operate on components each tick or on events), ");
        sb.append("and a ComponentRegistry / EntityManager that attaches/detaches components to Bukkit entities by UUID. ");
        sb.append("Place components in a 'component' sub-package, systems in a 'system' sub-package, and the registry in 'ecs'. ");
        sb.append("New behaviour = new Component + new System, never a sprawling god-class. ");
        sb.append("(C) ARCHETYPE LOADING: provide an ArchetypeLoader utility that reads YAML files from the server data dir at startup, ");
        sb.append("validates required keys, and caches parsed archetypes in a Map<String, ConfigurationSection>. ");
        sb.append("Systems reference archetypes by key, not by file path. ");

        sb.append("LIVE DEBUGGING VIA RCON: The Minecraft server exposes an RCON interface you can use to query ");
        sb.append("live in-world state while the server is running. ");
        sb.append("Connection: host=127.0.0.1, port=25575, password=<configured in server dir .env>. ");
        sb.append("To run a server command and read the response, use the short wrapper script: ");
        sb.append(mcScript).append(" \"<minecraft command>\". ");
        sb.append("Examples: ").append(mcScript).append(" \"list\" (who is online), ")
          .append(mcScript).append(" \"scoreboard players list\" (scores), ")
          .append(mcScript).append(" \"data get entity @a[limit=1] ActiveEffects\" (potion effects on a player). ");
        sb.append("Use RCON to observe actual in-world state when debugging or verifying that an enchant or ");
        sb.append("mechanic is working correctly. Always prefer RCON queries over guessing. ");

        sb.append("ASKING THE USER A QUESTION: When you need a multiple-choice decision from the user, ");
        sb.append("call the AskUserQuestion tool with one question and 2–6 short option labels. ");
        sb.append("This environment routes the question to a Minecraft client UI that shows clickable buttons; ");
        sb.append("the user's chosen option label arrives as their NEXT user message verbatim. ");
        sb.append("Note: the underlying CLI auto-dismisses AskUserQuestion synchronously, so your current turn ends ");
        sb.append("immediately after the tool call. Do NOT add any follow-up text in the same turn — no apology, ");
        sb.append("no 'the prompt was dismissed', no explanation. Just call the tool and end. ");
        sb.append("The real answer will arrive as the user's next message. ");
        sb.append("Use questions sparingly — only when a decision genuinely requires user input. ");
        sb.append("Never ask for things you can decide yourself or look up. ");

        if (!allPluginPaths.isEmpty()) {
            sb.append("All registered plugins (you may build any of these): ");
            for (String path : allPluginPaths) {
                String name = new File(path).getName();
                sb.append(name).append(" — source: ").append(path)
                  .append(", build script: ").append(buildScriptPrefix)
                  .append(serverDir.getAbsolutePath()).append(sep)
                  .append("build-").append(name).append(buildScriptExt).append("\"; ");
            }
        }

        return sb.toString();
    }

    // Transient — set for the duration of one send() call only
    private Consumer<TerminalEvent> terminalCallback;

    public void send(String message, Consumer<Component> onEvent,
                     Consumer<TerminalEvent> onTerminalEvent)
            throws IOException, InterruptedException {
        this.terminalCallback = onTerminalEvent;
        this.suppressNextText = false;
        this.lastThinkingText = "";

        if ("hermes".equalsIgnoreCase(aiProvider)) {
            // Hermes CLI: hermes chat -q "message" -Q
            List<String> cmd = new ArrayList<>();
            cmd.add(hermesPath);
            cmd.add("chat");
            cmd.add("-q");
            cmd.add(message);
            cmd.add("-Q");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workingDir);
            pb.redirectErrorStream(true);
            if (terminalCallback != null) terminalCallback.accept(new TerminalEvent.StreamStart());
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean done = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new IOException("Hermes timed out after " + TIMEOUT_SECONDS + "s");
            }
            // Output as a single ClaudeText event
            String response = output.toString().trim();
            if (!response.isEmpty()) {
                List<Component> rendered = MarkdownRenderer.render(response);
                for (Component line : rendered) onEvent.accept(prefix().append(line));
                if (terminalCallback != null)
                    terminalCallback.accept(new TerminalEvent.ClaudeText(rendered, response));
            }
            if (terminalCallback != null) terminalCallback.accept(new TerminalEvent.StreamEnd());
            this.terminalCallback = null;
            this.lastThinkingText = "";
            hasSession = true;
            pendingToolNames.clear();
            return;
        }

        // Default: Claude CLI
        List<String> cmd = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) { cmd.add("cmd"); cmd.add("/c"); }
        cmd.add(claudePath);
        cmd.add("--print");
        cmd.add("--dangerously-skip-permissions");
        cmd.add("--verbose");
        cmd.add("--output-format"); cmd.add("stream-json");
        if (!hasSession) {
            cmd.add("--system-prompt");
            cmd.add(buildSystemPrompt());
        } else {
            cmd.add("--continue");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(false);
        if (terminalCallback != null) terminalCallback.accept(new TerminalEvent.StreamStart());
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Thread stderrDrain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                while (r.readLine() != null) { /* discard */ }
            } catch (IOException ignored) {}
        });
        stderrDrain.setDaemon(true);
        stderrDrain.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<Component> events = parseEvent(line.trim());
                for (Component c : events) onEvent.accept(c);
            }
        }
        boolean done = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IOException("Claude timed out after " + TIMEOUT_SECONDS + "s");
        }
        if (terminalCallback != null) terminalCallback.accept(new TerminalEvent.StreamEnd());
        this.terminalCallback = null;
        this.lastThinkingText = "";
        hasSession = true;
        pendingToolNames.clear();
    }

    public void reset() { hasSession = false; }

    // -------------------------------------------------------------------------

    private List<Component> parseEvent(String json) {
        List<Component> out = new ArrayList<>();
        if (json.isEmpty()) return out;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String type = obj.get("type").getAsString();

            switch (type) {
                case "assistant" -> parseAssistant(obj, out);
                case "tool_result" -> parseToolResult(obj, out);
                // "system" and "result" intentionally ignored
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void parseAssistant(JsonObject obj, List<Component> out) {
        JsonArray content = obj.getAsJsonObject("message")
                              .getAsJsonArray("content");
        if (content == null) return;
        for (JsonElement el : content) {
            JsonObject block = el.getAsJsonObject();
            String blockType = block.get("type").getAsString();
            if ("thinking".equals(blockType)) {
                String text = block.has("thinking") ? block.get("thinking").getAsString() : "";
                if (!text.isBlank()) {
                    String delta = extractThinkingDelta(text);
                    if (delta.isBlank()) continue;
                    for (String segment : splitLines(delta)) {
                        out.add(Component.text("  ┆ ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(segment, NamedTextColor.DARK_GRAY)
                                        .decorate(TextDecoration.ITALIC)));
                    }
                    if (terminalCallback != null)
                        terminalCallback.accept(new TerminalEvent.Thinking(delta));
                }
            } else if ("text".equals(blockType)) {
                String text = block.get("text").getAsString().trim();
                if (text.isEmpty()) continue;
                if (suppressNextText) {
                    // Auto-dismissed AskUserQuestion produced filler text — drop it.
                    suppressNextText = false;
                    continue;
                }
                List<Component> rendered = MarkdownRenderer.render(text);
                for (Component line : rendered) out.add(prefix().append(line));
                if (terminalCallback != null)
                    terminalCallback.accept(new TerminalEvent.ClaudeText(rendered, text));
            } else if ("tool_use".equals(blockType)) {
                String id   = block.get("id").getAsString();
                String name = block.get("name").getAsString();
                pendingToolNames.put(id, name);
                JsonObject input = block.getAsJsonObject("input");

                if ("AskUserQuestion".equals(name) && handleAskUserQuestion(input, out)) {
                    suppressNextText = true;
                    continue;
                }

                out.add(formatToolCall(name, input));
                if (terminalCallback != null) {
                    // TODO: consolidate — duplicated tool-type switch exists in ClaudeSession, ClaudeCommand, and ClaudeTerminalUI; extract shared logic
                    String detail = switch (name) {
                        case "Read", "Write", "Edit" -> shortPath(getString(input, "file_path"));
                        case "Bash", "PowerShell"    -> truncate(getString(input, "command"), 60);
                        case "Glob", "Grep"          -> getString(input, "pattern");
                        default                      -> "";
                    };
                    terminalCallback.accept(new TerminalEvent.ToolCall(name, detail));
                }
            }
        }
    }

    /** Extracts the first question's prompt + option labels from an AskUserQuestion tool_use
     *  input, emits a Question event, and adds a chat-visible representation.
     *  Returns true if the call was successfully intercepted. */
    private boolean handleAskUserQuestion(JsonObject input, List<Component> out) {
        if (input == null || !input.has("questions")) return false;
        JsonArray questions = input.getAsJsonArray("questions");
        if (questions.isEmpty()) return false;
        JsonObject q = questions.get(0).getAsJsonObject();
        String prompt = q.has("question") ? q.get("question").getAsString() : "";
        List<String> options = new ArrayList<>();
        if (q.has("options")) {
            for (JsonElement el : q.getAsJsonArray("options")) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("label")) options.add(o.get("label").getAsString());
            }
        }
        if (prompt.isEmpty() || options.isEmpty()) return false;

        out.add(prefix().append(Component.text("? " + prompt, NamedTextColor.YELLOW)));
        for (int i = 0; i < options.size(); i++) {
            out.add(prefix().append(Component.text(
                    "  [" + (char)('A' + i) + "] " + options.get(i),
                    NamedTextColor.AQUA)));
        }
        if (terminalCallback != null)
            terminalCallback.accept(new TerminalEvent.Question(prompt, options));
        return true;
    }

    private void parseToolResult(JsonObject obj, List<Component> out) {
        String id = obj.has("tool_use_id") ? obj.get("tool_use_id").getAsString() : "";
        String toolName = pendingToolNames.getOrDefault(id, "");

        // Only surface Bash output — file contents are too verbose
        if (!"Bash".equalsIgnoreCase(toolName)) return;

        JsonElement contentEl = obj.get("content");
        if (contentEl == null) return;

        String text = "";
        if (contentEl.isJsonArray()) {
            for (JsonElement el : contentEl.getAsJsonArray()) {
                if (el.isJsonObject() && "text".equals(
                        el.getAsJsonObject().get("type").getAsString())) {
                    text = el.getAsJsonObject().get("text").getAsString();
                    break;
                }
            }
        } else if (contentEl.isJsonPrimitive()) {
            text = contentEl.getAsString();
        }

        List<String> outputLines = splitLines(text);
        for (String line : outputLines) {
            out.add(Component.text("  │ " + line, NamedTextColor.DARK_GRAY));
        }
        if (terminalCallback != null && !outputLines.isEmpty())
            terminalCallback.accept(new TerminalEvent.BashOutput(outputLines));
    }

    private Component formatToolCall(String name, JsonObject input) {
        String detail = switch (name) {
            case "Read"       -> shortPath(getString(input, "file_path"));
            case "Write"      -> shortPath(getString(input, "file_path"));
            case "Edit"       -> shortPath(getString(input, "file_path"));
            case "Bash",
                 "PowerShell" -> truncate(getString(input, "command"), 80);
            case "Glob"       -> getString(input, "pattern");
            case "Grep"       -> getString(input, "pattern");
            default           -> "";
        };

        String hoverFull = switch (name) {
            case "Read", "Write", "Edit" -> getString(input, "file_path");
            case "Bash", "PowerShell"    -> getString(input, "command");
            default                      -> "";
        };

        NamedTextColor labelColor = switch (name) {
            case "Write"      -> NamedTextColor.GREEN;
            case "Edit"       -> NamedTextColor.YELLOW;
            case "Bash",
                 "PowerShell" -> NamedTextColor.GOLD;
            default           -> NamedTextColor.GRAY;
        };

        Component label = Component.text("[" + name + "]", labelColor)
                .decorate(TextDecoration.BOLD);
        Component detailComp = detail.isEmpty()
                ? Component.empty()
                : Component.text(" " + detail, NamedTextColor.GRAY);
        Component result = Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(label).append(detailComp);
        if (!hoverFull.isEmpty()) {
            result = result.hoverEvent(HoverEvent.showText(
                    Component.text(hoverFull, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        return result;
    }

    private static Component prefix() {
        return Component.text("[Claude] ", NamedTextColor.AQUA);
    }

    private static String getString(JsonObject obj, String key) {
        return (obj != null && obj.has(key)) ? obj.get(key).getAsString() : "";
    }

    private static String shortPath(String path) {
        if (path == null || path.isEmpty()) return "";
        // Show only the filename for readability
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String extractThinkingDelta(String currentText) {
        if (lastThinkingText == null || lastThinkingText.isEmpty()) {
            lastThinkingText = currentText;
            return currentText;
        }

        if (currentText.startsWith(lastThinkingText)) {
            String delta = currentText.substring(lastThinkingText.length());
            lastThinkingText = currentText;
            return delta;
        }

        // Model replaced or rewrote the thought buffer; emit fresh text and resync baseline.
        lastThinkingText = currentText;
        return currentText;
    }

    private static List<String> splitLines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\n")) {
            line = line.stripTrailing();
            if (line.isEmpty()) continue;
            while (line.length() > MAX_LINE) {
                result.add(line.substring(0, MAX_LINE));
                line = line.substring(MAX_LINE);
            }
            result.add(line);
        }
        return result;
    }
}

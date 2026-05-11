package com.example.vibecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
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
    private final String serverPluginsDir;
    private final String restartFlagPath;
    private final File serverDir;
    private final List<String> allPluginPaths;
    private boolean hasSession;

    // Maps tool_use_id -> tool_name so tool_result events know what produced them
    private final Map<String, String> pendingToolNames = new HashMap<>();

    public ClaudeSession(File workingDir, String claudePath,
                         String serverPluginsDir, String restartFlagPath,
                         File serverDir, List<String> allPluginPaths,
                         boolean hasSession) {
        this.workingDir = workingDir;
        this.claudePath = claudePath;
        this.serverPluginsDir = serverPluginsDir;
        this.restartFlagPath = restartFlagPath;
        this.serverDir = serverDir;
        this.allPluginPaths = allPluginPaths;
        this.hasSession = hasSession;
    }

    public File getWorkingDir() { return workingDir; }
    public boolean isHasSession() { return hasSession; }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Minecraft Paper plugin developer assistant. ");
        sb.append("Working directory: ").append(workingDir.getAbsolutePath()).append(". ");
        sb.append("You have explicit permission to: ");
        sb.append("(1) Edit any source file in the project. ");
        sb.append("(2) Build THIS plugin: run `cmd /c gradlew.bat build` in the working directory. ");
        sb.append("(3) Deploy THIS plugin: copy the jar from build\\libs\\ to ").append(serverPluginsDir).append(". ");
        sb.append("(4) Restart the server: create the file ").append(restartFlagPath)
          .append(" — the server detects this and restarts automatically. ");
        sb.append("(5) Build any OTHER registered plugin by running its dedicated script in the server directory. ");
        sb.append("Always build and deploy before restarting. Do not ask for confirmation before taking these actions. ");

        if (!allPluginPaths.isEmpty()) {
            sb.append("All registered plugins (you may build any of these): ");
            for (String path : allPluginPaths) {
                String name = new File(path).getName();
                sb.append(name).append(" — source: ").append(path)
                  .append(", build script: cmd /c \"").append(serverDir.getAbsolutePath())
                  .append("\\build-").append(name).append(".bat\"; ");
            }
        }

        return sb.toString();
    }

    public void send(String message, Consumer<Component> onEvent)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd"); cmd.add("/c"); cmd.add(claudePath);
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
        cmd.add(message);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        // Keep stderr separate so it doesn't corrupt the JSON stream
        pb.redirectErrorStream(false);

        Process process = pb.start();
        process.getOutputStream().close(); // signal no stdin

        // Drain stderr — errors are visible in the session log file
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
                String text = block.has("thinking") ? block.get("thinking").getAsString().trim() : "";
                if (!text.isEmpty()) {
                    for (String segment : splitLines(text)) {
                        out.add(Component.text("  ┆ ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(segment, NamedTextColor.DARK_GRAY)
                                        .decorate(TextDecoration.ITALIC)));
                    }
                }
            } else if ("text".equals(blockType)) {
                String text = block.get("text").getAsString().trim();
                if (!text.isEmpty()) {
                    for (Component line : MarkdownRenderer.render(text)) {
                        out.add(prefix().append(line));
                    }
                }
            } else if ("tool_use".equals(blockType)) {
                String id   = block.get("id").getAsString();
                String name = block.get("name").getAsString();
                pendingToolNames.put(id, name);
                JsonObject input = block.getAsJsonObject("input");
                out.add(formatToolCall(name, input));
            }
        }
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

        for (String line : splitLines(text)) {
            out.add(Component.text("  │ " + line, NamedTextColor.DARK_GRAY));
        }
    }

    private Component formatToolCall(String name, JsonObject input) {
        String detail = switch (name) {
            case "Read"   -> shortPath(getString(input, "file_path"));
            case "Write"  -> shortPath(getString(input, "file_path"));
            case "Edit"   -> shortPath(getString(input, "file_path"));
            case "Bash"   -> truncate(getString(input, "command"), 80);
            case "Glob"   -> getString(input, "pattern");
            case "Grep"   -> getString(input, "pattern");
            default       -> "";
        };

        NamedTextColor labelColor = switch (name) {
            case "Write" -> NamedTextColor.GREEN;
            case "Edit"  -> NamedTextColor.YELLOW;
            case "Bash"  -> NamedTextColor.GOLD;
            default      -> NamedTextColor.GRAY;
        };

        Component label = Component.text("[" + name + "]", labelColor)
                .decorate(TextDecoration.BOLD);
        Component detailComp = detail.isEmpty()
                ? Component.empty()
                : Component.text(" " + detail, NamedTextColor.GRAY);
        return Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(label).append(detailComp);
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

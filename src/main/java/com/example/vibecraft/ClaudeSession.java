package com.example.vibecraft;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ClaudeSession {

    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_CHAT_LINE = 200;
    private static final java.util.regex.Pattern ANSI =
            java.util.regex.Pattern.compile("\\x1B\\[[\\d;]*[a-zA-Z]");

    private final File workingDir;
    private final String claudePath;
    private final String serverPluginsDir;
    private final String restartFlagPath;
    private boolean hasSession = false;

    public ClaudeSession(File workingDir, String claudePath,
                         String serverPluginsDir, String restartFlagPath) {
        this.workingDir = workingDir;
        this.claudePath = claudePath;
        this.serverPluginsDir = serverPluginsDir;
        this.restartFlagPath = restartFlagPath;
    }

    public File getWorkingDir() {
        return workingDir;
    }

    private String buildSystemPrompt() {
        return "You are a Minecraft Paper plugin developer assistant. " +
               "Working directory: " + workingDir.getAbsolutePath() + ". " +
               "You have explicit permission to: " +
               "(1) Edit any source file in the project. " +
               "(2) Build the plugin: run `cmd /c gradlew.bat build` in the working directory. " +
               "(3) Deploy the plugin: copy the jar from build\\libs\\ to " + serverPluginsDir + ". " +
               "(4) Restart the server: create the file " + restartFlagPath +
               " — the server detects this and restarts automatically. " +
               "Always build and deploy before restarting. Do not ask for confirmation before taking these actions.";
    }

    public List<String> send(String message) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd");
        cmd.add("/c");
        cmd.add(claudePath);
        cmd.add("--print");
        cmd.add("--dangerously-skip-permissions");
        if (!hasSession) {
            cmd.add("--system-prompt");
            cmd.add(buildSystemPrompt());
        } else {
            cmd.add("--continue");
        }
        cmd.add(message);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = ANSI.matcher(line).replaceAll("").trim();
                if (clean.isEmpty()) continue;
                while (clean.length() > MAX_CHAT_LINE) {
                    lines.add(clean.substring(0, MAX_CHAT_LINE));
                    clean = clean.substring(MAX_CHAT_LINE);
                }
                lines.add(clean);
            }
        }

        boolean done = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IOException("Claude timed out after " + TIMEOUT_SECONDS + "s");
        }

        hasSession = true;
        return lines.isEmpty() ? List.of("(no response)") : lines;
    }

    public void reset() {
        hasSession = false;
    }
}

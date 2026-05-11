package com.example.vibecraft;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ClaudeSession {

    private static final int TIMEOUT_SECONDS = 120;
    private static final int MAX_CHAT_LINE = 200;
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\[[\\d;]*[a-zA-Z]");

    private static final String SYSTEM_PROMPT =
        "You are a Minecraft Paper plugin developer assistant working on the VibeCraft plugin. " +
        "Working directory: C:\\Users\\jonat\\minecraft\\VibeCraft. " +
        "You have explicit permission to: " +
        "(1) Edit any source file in the project. " +
        "(2) Build the plugin by running: cmd /c gradlew.bat build " +
        "(3) Deploy the plugin by copying build\\libs\\VibeCraft-1.0-SNAPSHOT.jar to C:\\Users\\jonat\\minecraft\\server\\plugins\\ " +
        "(4) Restart the server by creating the file C:\\Users\\jonat\\minecraft\\server\\restart.flag — " +
        "the server detects this file and restarts automatically. " +
        "Always build and deploy before restarting. Do not ask for confirmation before taking these actions.";

    private final File workingDir;
    private final String claudePath;
    private boolean hasSession = false;

    public ClaudeSession(File workingDir, String claudePath) {
        this.workingDir = workingDir;
        this.claudePath = claudePath;
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
            cmd.add(SYSTEM_PROMPT);
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

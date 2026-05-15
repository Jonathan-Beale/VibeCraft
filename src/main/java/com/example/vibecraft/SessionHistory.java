package com.example.vibecraft;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SessionHistory {

    public enum Type { USER, CLAUDE, THINKING, TOOL, BASH, SYSTEM }

    public record Entry(Type type, String header, String body) {}

    private static final int MAX_TOOL_BODY = 16000;

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public SessionHistory(File file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", 3);
                if (parts.length < 1) continue;
                try {
                    Type type   = Type.valueOf(parts[0]);
                    String header = parts.length > 1 ? unescape(parts[1]) : "";
                    String body   = parts.length > 2 ? unescape(parts[2]) : "";
                    entries.add(new Entry(type, header, body));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    public void append(Entry entry) {
        entries.add(entry);
        try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
            String rawBody = entry.body() == null ? "" : entry.body();
            String body = (entry.type() == Type.TOOL && rawBody.length() > MAX_TOOL_BODY)
                ? rawBody.substring(0, MAX_TOOL_BODY) + "…"
                : rawBody;
            w.printf("%s\t%s\t%s%n",
                    entry.type(),
                    escape(entry.header()),
                    escape(body));
        } catch (IOException ignored) {}
    }

    public List<Entry> entries() { return List.copyOf(entries); }

    public void clear() {
        entries.clear();
        file.delete();
    }

    public static File fileFor(File dataFolder, UUID uuid) {
        File dir = new File(dataFolder, "sessions");
        dir.mkdirs();
        return new File(dir, uuid + ".hist");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (char c : s.toCharArray()) {
            if (esc) {
                switch (c) {
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    default   -> { sb.append('\\'); sb.append(c); }
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

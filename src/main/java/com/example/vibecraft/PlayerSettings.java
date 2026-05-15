package com.example.vibecraft;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerSettings {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("chat.user_messages",  "true");
        DEFAULTS.put("chat.claude_text",    "true");
        DEFAULTS.put("chat.tools",          "false");
        DEFAULTS.put("chat.bash",           "false");
        DEFAULTS.put("chat.thinking",       "false");
        DEFAULTS.put("hud.lines",           "1");
        DEFAULTS.put("ui.thoughts_visible", "true");
        DEFAULTS.put("ui.color_scheme",     "terminal");
        DEFAULTS.put("color.user",     "55FF55");
        DEFAULTS.put("color.claude",   "55FFFF");
        DEFAULTS.put("color.tool",     "FFAA00");
        DEFAULTS.put("color.output",   "888888");
        DEFAULTS.put("color.system",   "AAAAAA");
        DEFAULTS.put("color.question", "FFFF55");
    }

    private final File dataDir;

    public PlayerSettings(File pluginDataDir) {
        this.dataDir = new File(pluginDataDir, "player-settings");
        this.dataDir.mkdirs();
    }

    public String get(UUID uuid, String key) {
        YamlConfiguration cfg = load(uuid);
        return cfg.getString(key, DEFAULTS.getOrDefault(key, "false"));
    }

    public boolean getBool(UUID uuid, String key) {
        return Boolean.parseBoolean(get(uuid, key));
    }

    public void set(UUID uuid, String key, String value) {
        if (!DEFAULTS.containsKey(key)) return;
        File f = file(uuid);
        YamlConfiguration cfg = load(uuid);
        cfg.set(key, value);
        try { cfg.save(f); } catch (Exception ignored) {}
    }

    public Map<String, String> getAll(UUID uuid) {
        YamlConfiguration cfg = load(uuid);
        Map<String, String> result = new LinkedHashMap<>(DEFAULTS);
        for (String key : DEFAULTS.keySet()) {
            if (cfg.contains(key)) result.put(key, cfg.getString(key));
        }
        return result;
    }

    private YamlConfiguration load(UUID uuid) {
        File f = file(uuid);
        return f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
    }

    private File file(UUID uuid) {
        return new File(dataDir, uuid + ".yml");
    }
}

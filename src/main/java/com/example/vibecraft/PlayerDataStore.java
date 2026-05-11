package com.example.vibecraft;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PlayerDataStore {

    private final File dataFile;
    private YamlConfiguration config;

    public PlayerDataStore(File dataFolder) {
        this.dataFile = new File(dataFolder, "players.yml");
        reload();
    }

    private void reload() {
        config = dataFile.exists()
                ? YamlConfiguration.loadConfiguration(dataFile)
                : new YamlConfiguration();
    }

    public String getDefaultPath(UUID uuid) {
        return config.getString(uuid + ".default-path");
    }

    public void setDefaultPath(UUID uuid, String path) {
        config.set(uuid + ".default-path", path);
        save();
    }

    public boolean getHasSession(UUID uuid, String path) {
        return config.getStringList(uuid + ".active-sessions").contains(path);
    }

    public void setHasSession(UUID uuid, String path, boolean value) {
        List<String> list = new ArrayList<>(config.getStringList(uuid + ".active-sessions"));
        if (value) { if (!list.contains(path)) list.add(path); }
        else        { list.remove(path); }
        config.set(uuid + ".active-sessions", list.isEmpty() ? null : list);
        save();
    }

    public void clearSessions(UUID uuid) {
        config.set(uuid + ".active-sessions", null);
        save();
    }

    /** All distinct configured paths — used to seed onboarding suggestions. */
    public List<String> getAllConfiguredPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (String key : config.getKeys(false)) {
            String p = config.getString(key + ".default-path");
            if (p != null) paths.add(p);
        }
        return new ArrayList<>(paths);
    }

    private void save() {
        try {
            config.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

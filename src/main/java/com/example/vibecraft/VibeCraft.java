package com.example.vibecraft;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class VibeCraft extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String projectDir = getConfig().getString("project-dir",
                new File(getDataFolder(), "../../VibeCraft").getAbsolutePath());
        String claudePath = getConfig().getString("claude-path", "claude");

        ClaudeCommand handler = new ClaudeCommand(this, new File(projectDir), claudePath);
        getCommand("claude").setExecutor(handler);

        getServer().getScheduler().runTaskTimer(this,
                new RestartWatcher(this), 20L, 20L);

        getLogger().info("VibeCraft enabled! Claude: " + claudePath);
    }

    @Override
    public void onDisable() {
        getLogger().info("VibeCraft disabled!");
    }
}

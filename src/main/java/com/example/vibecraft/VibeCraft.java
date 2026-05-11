package com.example.vibecraft;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class VibeCraft extends JavaPlugin {

    private PlayerDataStore playerData;
    private ClaudeCommand claudeCommand;
    private String claudePath;
    private String serverPluginsDir;
    private String restartFlagPath;
    private File workspaceDir;
    private File selfDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        claudePath = getConfig().getString("claude-path",
                System.getenv("APPDATA") + "\\npm\\claude.cmd");
        serverPluginsDir = getConfig().getString("server-plugins-dir",
                new File(getDataFolder(), "../../server/plugins").getAbsolutePath() + "\\");
        restartFlagPath = getConfig().getString("restart-flag-path",
                new File(getDataFolder(), "../../server/restart.flag").getAbsolutePath());

        selfDir = new File(getConfig().getString("vibecraft-dir",
                new File(getDataFolder(), "../../VibeCraft").getAbsolutePath()));
        workspaceDir = selfDir.getParentFile();

        playerData = new PlayerDataStore(getDataFolder());
        claudeCommand = new ClaudeCommand(this);
        getCommand("claude").setExecutor(claudeCommand);

        getServer().getScheduler().runTaskTimer(this,
                new RestartWatcher(this), 20L, 20L);

        getLogger().info("VibeCraft enabled. Workspace: " + workspaceDir.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        getLogger().info("VibeCraft disabled.");
    }

    public PlayerDataStore getPlayerData() { return playerData; }
    public String getClaudePath() { return claudePath; }
    public String getServerPluginsDir() { return serverPluginsDir; }
    public String getRestartFlagPath() { return restartFlagPath; }
    public File getWorkspaceDir() { return workspaceDir; }
    public File getSelfDir() { return selfDir; }
}

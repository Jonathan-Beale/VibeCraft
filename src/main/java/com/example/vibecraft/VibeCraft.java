package com.example.vibecraft;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;

import java.io.File;

public class VibeCraft extends JavaPlugin {

    private PlayerDataStore playerData;
    private BuildScriptManager buildScripts;
    private ClaudeCommand claudeCommand;
    private String claudePath;
    private String serverPluginsDir;
    private String restartFlagPath;
    private File workspaceDir;
    private File selfDir;
        private PluginUIRegistry uiRegistry;

    @Override
    public void onEnable() {
        saveDefaultConfig();
                saveResource("ui/main.json", false);

        claudePath = getConfig().getString("claude-path",
                System.getenv("APPDATA") + "\\npm\\claude.cmd");
        serverPluginsDir = getConfig().getString("server-plugins-dir",
                new File(getDataFolder(), "../../server/plugins").getAbsolutePath() + "\\");
        restartFlagPath = getConfig().getString("restart-flag-path",
                new File(getDataFolder(), "../../server/restart.flag").getAbsolutePath());

        selfDir = new File(getConfig().getString("vibecraft-dir",
                new File(getDataFolder(), "../../VibeCraft").getAbsolutePath()));
        workspaceDir = selfDir.getParentFile();

        File serverDir = new File(getConfig().getString("server-dir",
                getDataFolder().getAbsoluteFile().getParentFile().getParentFile().getAbsolutePath()));

        playerData = new PlayerDataStore(getDataFolder());
        buildScripts = new BuildScriptManager(serverDir, serverPluginsDir);

        // Regenerate build scripts from all currently configured repos
        buildScripts.regenerate(playerData.getAllConfiguredPaths());

        uiRegistry = new PluginUIRegistry(getServer().getPluginManager());

        claudeCommand = new ClaudeCommand(this);
                PluginCommand command = getCommand("claude");
                if (command == null) {
                        getLogger().severe("Command 'claude' is not defined in plugin.yml; disabling plugin.");
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                }
                command.setExecutor(claudeCommand);
        getServer().getPluginManager().registerEvents(
                new TerminalInputListener(claudeCommand, this), this);

        getServer().getScheduler().runTaskTimer(this,
                new RestartWatcher(this), 20L, 20L);

        getServer().getMessenger().registerOutgoingPluginChannel(this, "vibecraft:events");
        getServer().getMessenger().registerIncomingPluginChannel(this, "vibecraft:input",
                new ModInputListener(claudeCommand));

        getLogger().info("VibeCraft enabled. Workspace: " + workspaceDir.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, "vibecraft:events");
        getServer().getMessenger().unregisterIncomingPluginChannel(this, "vibecraft:input");
        getLogger().info("VibeCraft disabled.");
    }

    public ClaudeCommand getClaudeCommand() { return claudeCommand; }
    public PlayerDataStore getPlayerData() { return playerData; }
    public BuildScriptManager getBuildScripts() { return buildScripts; }
    public String getClaudePath() { return claudePath; }
    public String getServerPluginsDir() { return serverPluginsDir; }
    public String getRestartFlagPath() { return restartFlagPath; }
    public File getWorkspaceDir() { return workspaceDir; }
    public File getSelfDir() { return selfDir; }
        public PluginUIRegistry getUiRegistry() { return uiRegistry; }
}

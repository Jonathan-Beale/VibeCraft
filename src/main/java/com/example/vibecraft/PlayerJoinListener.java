package com.example.vibecraft;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Pushes the full UI schema + settings to the mod as soon as a player joins.
 * The 40-tick delay gives the client time to register the plugin message channel.
 */
public class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final ClaudeCommand claudeCommand;

    public PlayerJoinListener(JavaPlugin plugin, ClaudeCommand claudeCommand) {
        this.plugin = plugin;
        this.claudeCommand = claudeCommand;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay to allow the client's plugin channel to finish registering.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                claudeCommand.sendHistoryAndSettingsToMod(player);
            }
        }, 40L); // 2 seconds
    }
}

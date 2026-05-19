package com.example.vibecraft;

import org.bukkit.Bukkit;

import java.io.File;

public class RestartWatcher implements Runnable {

    private final File FLAG;

    private final VibeCraft plugin;

    public RestartWatcher(VibeCraft plugin) {
        this.plugin = plugin;
        this.FLAG = new File(plugin.getRestartFlagPath());
    }

    @Override
    public void run() {
        if (!FLAG.exists()) return;
        FLAG.delete();
        plugin.getLogger().info("restart.flag detected — restarting server...");
        Bukkit.broadcast(
            net.kyori.adventure.text.Component.text("§eServer restarting for plugin update..."),
            "minecraft.command.op"
        );
        Bukkit.getServer().restart();
    }
}

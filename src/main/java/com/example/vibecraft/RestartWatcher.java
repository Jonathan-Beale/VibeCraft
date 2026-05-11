package com.example.vibecraft;

import org.bukkit.Bukkit;

import java.io.File;

public class RestartWatcher implements Runnable {

    private static final File FLAG = new File("C:\\Users\\jonat\\minecraft\\server\\restart.flag");

    private final VibeCraft plugin;

    public RestartWatcher(VibeCraft plugin) {
        this.plugin = plugin;
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

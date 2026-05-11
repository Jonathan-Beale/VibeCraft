package com.example.vibecraft;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.List;

public class ClaudeCommand implements CommandExecutor {

    private static final String PREFIX = "§8[§bClaude§8] §f";
    private static final String ERR_PREFIX = "§8[§bClaude§8] §c";

    private final VibeCraft plugin;
    private final ClaudeSession session;

    public ClaudeCommand(VibeCraft plugin, File projectDir, String claudePath) {
        this.plugin = plugin;
        this.session = new ClaudeSession(projectDir, claudePath);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ERR_PREFIX + "Operators only.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ERR_PREFIX + "Usage: /claude <message>  |  /claude reset");
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            session.reset();
            sender.sendMessage(PREFIX + "Session reset.");
            return true;
        }

        String message = String.join(" ", args);
        sender.sendMessage(PREFIX + "§7Thinking...");

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<String> lines = session.send(message);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (String line : lines) {
                        sender.sendMessage(PREFIX + line);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("Claude error: " + e.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ERR_PREFIX + e.getMessage())
                );
            }
        });

        return true;
    }
}

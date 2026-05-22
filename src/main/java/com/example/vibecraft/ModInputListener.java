package com.example.vibecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;

public class ModInputListener implements PluginMessageListener {

    private final ClaudeCommand claudeCommand;

    public ModInputListener(ClaudeCommand claudeCommand) {
        this.claudeCommand = claudeCommand;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            String json = new String(message, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String targetPlugin = obj.has("plugin") ? obj.get("plugin").getAsString() : "vibecraft";
            if (!"vibecraft".equalsIgnoreCase(targetPlugin)) {
                return;
            }

            // TODO: replace if-chain with handler map — violates registry/dispatch design principle
            if (obj.has("type")) {
                String type = obj.get("type").getAsString();
                if ("request_history".equals(type)) {
                    claudeCommand.sendHistoryAndSettingsToMod(player);
                    return;
                }
                if ("set_setting".equals(type)
                        && obj.has("key") && obj.has("value")) {
                    claudeCommand.applySetting(player,
                            obj.get("key").getAsString(),
                            obj.get("value").getAsString());
                    return;
                }
                if ("clear_history".equals(type)) {
                    claudeCommand.clearHistoryFor(player);
                    return;
                }
            }

            if (obj.has("message")) {
                String text = obj.get("message").getAsString().trim();
                if (!text.isEmpty()) {
                    claudeCommand.submitFromMod(player, text);
                }
            }
        } catch (Exception ignored) {}
    }
}

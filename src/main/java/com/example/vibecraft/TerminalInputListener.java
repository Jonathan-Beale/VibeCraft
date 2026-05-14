package com.example.vibecraft;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class TerminalInputListener implements Listener {

    private final ClaudeCommand command;
    private final VibeCraft plugin;

    public TerminalInputListener(ClaudeCommand command, VibeCraft plugin) {
        this.command = command;
        this.plugin = plugin;
    }

    // Block all click/drag interactions inside the terminal inventory
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ClaudeTerminalUI terminal = command.getTerminal(player);
        if (terminal == null || !terminal.getInventory().equals(event.getInventory())) return;
        event.setCancelled(true);

        // Only react to clicks in the control row (slots 45–53), not message area
        int slot = event.getRawSlot();
        if (slot >= 45 && slot <= 53) {
            terminal.handleControlClick(slot, command);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ClaudeTerminalUI terminal = command.getTerminal(player);
        if (terminal != null && terminal.getInventory().equals(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        ClaudeTerminalUI terminal = command.getTerminal(player);
        if (terminal != null && terminal.getInventory() != null
                && terminal.getInventory().equals(event.getInventory())) {
            terminal.onClose();
        }
    }

    // Intercept chat when the player is in compose mode
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ClaudeTerminalUI terminal = command.getTerminal(player);
        if (terminal == null || !terminal.isComposing()) return;

        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        event.setCancelled(true);

        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("/cancel")) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                terminal.open();
                terminal.addSystemMessage("Cancelled.");
            });
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () ->
            command.submitFromTerminal(player, text));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        command.removeTerminal(event.getPlayer());
    }
}

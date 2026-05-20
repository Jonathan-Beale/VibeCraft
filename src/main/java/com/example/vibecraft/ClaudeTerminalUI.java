package com.example.vibecraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClaudeTerminalUI {

    private static final int DISPLAY_SLOTS = 45; // rows 0–4 (5 × 9)
    private static final int WRAP_WIDTH    = 48;
    private final String providerName;
    private final Component titleComponent;

    private record Message(Material mat, Component header, List<Component> lore) {}

    private final Player player;
    private final VibeCraft plugin;
    private final SessionHistory history;

    private Inventory inv;
    private final List<Message> messages = new ArrayList<>();
    private final List<SessionHistory.Entry> allEntries = new ArrayList<>();
    private int viewStart = 0;
    private boolean pinnedToBottom = true;
    private boolean isOpen = false;
    private boolean composing = false;
    private boolean streaming = false;

    public ClaudeTerminalUI(Player player, VibeCraft plugin, SessionHistory history, String providerName) {
        this.player = player;
        this.plugin = plugin;
        this.history = history;
        this.providerName = providerName;
        this.titleComponent = Component.text("◆ " + providerName + " Terminal", NamedTextColor.AQUA);
        replayHistory();
    }

    private void replayHistory() {
        for (SessionHistory.Entry e : history.entries()) {
            allEntries.add(e);
            messages.add(entryToMessage(e));
        }
        if (!messages.isEmpty()) {
            viewStart = Math.max(0, messages.size() - DISPLAY_SLOTS);
        }
    }

    public List<SessionHistory.Entry> getEntries() { return List.copyOf(allEntries); }

    private Message entryToMessage(SessionHistory.Entry e) {
        return switch (e.type()) {
            case USER -> new Message(
                Material.PAPER,
                Component.text("▶ You  ", NamedTextColor.GREEN)
                    .append(Component.text(truncate(e.header(), 38), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, false),
                wrap(e.body(), NamedTextColor.WHITE));
            case CLAUDE -> new Message(
                Material.WRITTEN_BOOK,
                Component.text("◆ " + providerName + "  ", NamedTextColor.AQUA)
                    .append(Component.text(truncate(e.header(), 35), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, false),
                wrap(e.body(), NamedTextColor.WHITE));
            case THINKING -> new Message(
                Material.GRAY_DYE,
                Component.text("┆ Thinking", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, true)
                    .decoration(TextDecoration.BOLD, false),
                wrap(e.body(), NamedTextColor.DARK_GRAY));
            case TOOL -> {
                int sep = e.header().indexOf('|');
                String toolName = sep >= 0 ? e.header().substring(0, sep) : e.header();
                String detail   = sep >= 0 ? e.header().substring(sep + 1) : "";
                var style = toolStyle(toolName);
                Component header = Component.text("[" + toolName + "]", style.color())
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false);
                if (!detail.isEmpty()) {
                    header = header.append(Component.text("  " + detail, NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false));
                }
                yield new Message(style.mat(), header, Collections.emptyList());
            }
            case BASH -> new Message(
                Material.COMPARATOR,
                Component.text("│ Output", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, false),
                wrap(e.body(), NamedTextColor.DARK_GRAY));
            case SYSTEM -> new Message(
                Material.COMMAND_BLOCK,
                Component.text("⚙ " + e.header(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Collections.emptyList());
        };
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    public void open() {
        composing = false;
        isOpen = true;
        inv = Bukkit.createInventory(null, 54, titleComponent);
        render();
        player.openInventory(inv);
    }

    public void onClose() {
        if (!composing) isOpen = false;
    }

    public boolean isOpen()      { return isOpen; }
    public boolean isComposing() { return composing; }
    public Inventory getInventory() { return inv; }

    // -------------------------------------------------------------------------
    // Called by ClaudeCommand before firing off a session

    public void addUserMessage(String text) {
        SessionHistory.Entry entry = new SessionHistory.Entry(SessionHistory.Type.USER, truncate(text, 38), text);
        history.append(entry);
        allEntries.add(entry);
        List<Component> lore = wrap(text, NamedTextColor.WHITE);
        messages.add(new Message(
            Material.PAPER,
            Component.text("▶ You  ", NamedTextColor.GREEN)
                .append(Component.text(truncate(text, 38), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, false),
            lore
        ));
        autoScroll();
        render();
    }

    // -------------------------------------------------------------------------
    // Streaming event receiver (called on main thread by ClaudeCommand)

    public void onEvent(TerminalEvent event) {
        switch (event) {
            case TerminalEvent.StreamStart e -> {
                streaming = true;
                render();
            }
            case TerminalEvent.Thinking e -> {
                if (!e.text().isBlank()) {
                    // Thinking goes to allEntries (for mod history replay) but not the persistent history file
                    allEntries.add(new SessionHistory.Entry(SessionHistory.Type.THINKING, "", e.text()));
                    messages.add(new Message(
                        Material.GRAY_DYE,
                        Component.text("┆ Thinking", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, true)
                            .decoration(TextDecoration.BOLD, false),
                        wrap(e.text(), NamedTextColor.DARK_GRAY)
                    ));
                    autoScroll();
                    render();
                }
            }
            case TerminalEvent.ClaudeText e -> {
                String raw = e.rawText();
                String firstRaw = raw.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
                String headerText = firstRaw.replaceAll("^#+\\s+", "")
                        .replaceAll("^>\\s+", "").replaceAll("^[-*]\\s+", "");
                SessionHistory.Entry claudeEntry = new SessionHistory.Entry(
                    SessionHistory.Type.CLAUDE, truncate(headerText, 35), raw);
                history.append(claudeEntry);
                allEntries.add(claudeEntry);
                List<Component> lore = new ArrayList<>();
                for (Component c : e.lines()) {
                    lore.add(c.decoration(TextDecoration.ITALIC, false));
                }
                messages.add(new Message(
                    Material.WRITTEN_BOOK,
                    Component.text("◆ " + providerName + "  ", NamedTextColor.AQUA)
                        .append(Component.text(truncate(headerText, 35), NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, false),
                    lore
                ));
                autoScroll();
                render();
            }
            case TerminalEvent.ToolCall e -> {
                SessionHistory.Entry toolEntry = new SessionHistory.Entry(
                    SessionHistory.Type.TOOL, e.toolName() + "|" + e.detail(), "");
                history.append(toolEntry);
                allEntries.add(toolEntry);
                var style = toolStyle(e.toolName());
                Component header = Component.text("[" + e.toolName() + "]", style.color())
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false);
                if (!e.detail().isEmpty()) {
                    header = header.append(Component.text("  " + e.detail(), NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false));
                }
                messages.add(new Message(style.mat(), header, Collections.emptyList()));
                autoScroll();
                render();
            }
            case TerminalEvent.BashOutput e -> {
                if (e.lines().isEmpty()) break;
                SessionHistory.Entry bashEntry = new SessionHistory.Entry(
                    SessionHistory.Type.BASH, "", String.join("\n", e.lines()));
                history.append(bashEntry);
                allEntries.add(bashEntry);
                List<Component> lore = new ArrayList<>();
                for (String l : e.lines()) {
                    lore.add(Component.text(l, NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                }
                messages.add(new Message(
                    Material.COMPARATOR,
                    Component.text("│ Output", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, false),
                    lore
                ));
                autoScroll();
                render();
            }
            case TerminalEvent.StreamEnd e -> {
                streaming = false;
                render();
            }
            case TerminalEvent.Question e -> {
                String optionLines = e.options().stream()
                        .map(o -> "  • " + o)
                        .collect(Collectors.joining("\n"));
                SessionHistory.Entry questionEntry = new SessionHistory.Entry(
                        SessionHistory.Type.CLAUDE, "? " + e.prompt(), optionLines);
                history.append(questionEntry);
                allEntries.add(questionEntry);
                List<Component> lore = new ArrayList<>();
                for (int i = 0; i < e.options().size(); i++) {
                    lore.add(Component.text("[" + (char)('A' + i) + "] " + e.options().get(i),
                            NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                }
                messages.add(new Message(
                        Material.PAPER,
                        Component.text("? " + truncate(e.prompt(), 35), NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false),
                        lore));
                autoScroll();
                render();
            }
        }
    }

    public void addSystemMessage(String text) {
        SessionHistory.Entry entry = new SessionHistory.Entry(SessionHistory.Type.SYSTEM, text, "");
        history.append(entry);
        allEntries.add(entry);
        messages.add(new Message(
            Material.COMMAND_BLOCK,
            Component.text("⚙ " + text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Collections.emptyList()
        ));
        autoScroll();
        render();
    }

    public void clearHistory() {
        history.clear();
        messages.clear();
        allEntries.clear();
        streaming = false;
        viewStart = 0;
        render();
    }

    // -------------------------------------------------------------------------
    // Control interactions (called by TerminalInputListener on main thread)

    public void handleControlClick(int slot, ClaudeCommand command) {
        switch (slot) {
            case 45 -> scrollNewer();
            case 46 -> scrollOlder();
            case 48 -> startCompose();
            case 50 -> {
                messages.clear();
                allEntries.clear();
                viewStart = 0;
                streaming = false;
                pinnedToBottom = true;
                history.clear();
                command.resetSession(player);
                addSystemMessage("Session reset.");
            }
            // slot 52 (repo) — read-only info for now
        }
    }

    // -------------------------------------------------------------------------
    // Private rendering

    private void render() {
        if (!isOpen || inv == null) return;

        for (int i = 0; i < DISPLAY_SLOTS; i++) inv.setItem(i, null);

        int end = Math.min(messages.size(), viewStart + DISPLAY_SLOTS);
        for (int i = viewStart; i < end; i++) {
            inv.setItem(i - viewStart, makeItem(messages.get(i)));
        }

        if (streaming) {
            int nextSlot = end - viewStart;
            if (nextSlot < DISPLAY_SLOTS) {
                inv.setItem(nextSlot, streamingItem());
            }
        }

        renderControls();
        player.updateInventory();
    }

    private void renderControls() {
        inv.setItem(45, ctrl(Material.LIME_CONCRETE,
            Component.text("▲ Newer", NamedTextColor.GREEN),
            Component.text("Scroll toward newer messages", NamedTextColor.GRAY)));

        inv.setItem(46, ctrl(Material.BLUE_CONCRETE,
            Component.text("▼ Older", NamedTextColor.AQUA),
            Component.text("Scroll toward older messages", NamedTextColor.GRAY)));

        inv.setItem(47, spacer());

        inv.setItem(48, ctrl(Material.FEATHER,
            Component.text("✏  Type a message", NamedTextColor.YELLOW),
            Component.text("Close terminal then type in chat", NamedTextColor.GRAY)));

        inv.setItem(49, spacer());

        inv.setItem(50, ctrl(Material.BARRIER,
            Component.text("↺ Reset Session", NamedTextColor.RED),
            Component.text("Clear history and start a new session", NamedTextColor.GRAY)));

        inv.setItem(51, spacer());

        String repoPath = plugin.getPlayerData().getDefaultPath(player.getUniqueId());
        String repoName = repoPath != null ? new File(repoPath).getName() : "none";
        inv.setItem(52, ctrl(Material.CHEST,
            Component.text("Repo: ", NamedTextColor.GRAY)
                .append(Component.text(repoName, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false),
            Component.text(repoPath != null ? repoPath : "Use /claude repo set <path>",
                NamedTextColor.DARK_GRAY)));

        inv.setItem(53, spacer());
    }

    private static final int MAX_LORE = 256;

    private ItemStack makeItem(Message msg) {
        ItemStack item = new ItemStack(msg.mat());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(msg.header());
        if (!msg.lore().isEmpty()) {
            List<Component> lore = msg.lore();
            if (lore.size() > MAX_LORE) {
                lore = new ArrayList<>(lore.subList(0, MAX_LORE - 1));
                lore.add(Component.text("… (" + (msg.lore().size() - (MAX_LORE - 1)) + " more lines)",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack streamingItem() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("⏳ Processing…", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack ctrl(Material mat, Component name, Component hint) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(hint.decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack spacer() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(""));
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Scroll & compose helpers

    private void autoScroll() {
        if (pinnedToBottom) {
            viewStart = Math.max(0, messages.size() - DISPLAY_SLOTS);
        }
    }

    private void scrollNewer() {
        int maxStart = Math.max(0, messages.size() - DISPLAY_SLOTS);
        viewStart = Math.min(maxStart, viewStart + 9);
        if (viewStart >= maxStart) pinnedToBottom = true;
        render();
    }

    private void scrollOlder() {
        if (viewStart <= 0) return;
        pinnedToBottom = false;
        viewStart = Math.max(0, viewStart - 9);
        render();
    }

    private void startCompose() {
        composing = true;
        player.closeInventory();
        player.sendActionBar(
            Component.text("✏  Type your message", NamedTextColor.YELLOW)
                .append(Component.text("  (or ", NamedTextColor.DARK_GRAY))
                .append(Component.text("/cancel", NamedTextColor.GRAY))
                .append(Component.text(")", NamedTextColor.DARK_GRAY)));
    }

    // -------------------------------------------------------------------------
    // Util

    private record ToolStyle(Material mat, NamedTextColor color) {}

    private static ToolStyle toolStyle(String name) {
        return switch (name) {
            case "Write"                  -> new ToolStyle(Material.GREEN_STAINED_GLASS_PANE,  NamedTextColor.GREEN);
            case "Edit"                   -> new ToolStyle(Material.YELLOW_STAINED_GLASS_PANE, NamedTextColor.YELLOW);
            case "Bash", "PowerShell"     -> new ToolStyle(Material.ORANGE_STAINED_GLASS_PANE, NamedTextColor.GOLD);
            case "Glob", "Grep"           -> new ToolStyle(Material.CYAN_STAINED_GLASS_PANE,   NamedTextColor.AQUA);
            default                       -> new ToolStyle(Material.GRAY_STAINED_GLASS_PANE,   NamedTextColor.GRAY);
        };
    }

    private static List<Component> wrap(String text, NamedTextColor color) {
        List<Component> result = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            paragraph = paragraph.stripTrailing();
            if (paragraph.isEmpty()) continue;
            while (paragraph.length() > WRAP_WIDTH) {
                int cut = paragraph.lastIndexOf(' ', WRAP_WIDTH);
                if (cut < 1) cut = WRAP_WIDTH;
                result.add(Component.text(paragraph.substring(0, cut), color)
                    .decoration(TextDecoration.ITALIC, false));
                paragraph = paragraph.substring(cut).stripLeading();
            }
            if (!paragraph.isEmpty()) {
                result.add(Component.text(paragraph, color)
                    .decoration(TextDecoration.ITALIC, false));
            }
        }
        return result;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

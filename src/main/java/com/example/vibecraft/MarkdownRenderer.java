package com.example.vibecraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

public class MarkdownRenderer {

    private static final int MAX_LINE = 4000;

    /**
     * Renders a multi-line markdown string into a list of formatted Components,
     * one per visual line. Does not add any prefix — callers do that.
     */
    public static List<Component> render(String text) {
        List<Component> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            raw = raw.stripTrailing();
            for (Component c : renderLine(raw)) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Component> renderLine(String line) {
        List<Component> out = new ArrayList<>();

        // Horizontal rule
        if (line.matches("^[-=]{3,}$")) {
            out.add(Component.text("─────────────────────", NamedTextColor.DARK_GRAY));
            return out;
        }

        // Headings: # ## ###
        if (line.startsWith("# ")) {
            out.add(inline(line.substring(2), NamedTextColor.GOLD, TextDecoration.BOLD));
            return out;
        }
        if (line.startsWith("## ")) {
            out.add(inline(line.substring(3), NamedTextColor.YELLOW, TextDecoration.BOLD));
            return out;
        }
        if (line.startsWith("### ")) {
            out.add(inline(line.substring(4), NamedTextColor.WHITE, TextDecoration.BOLD));
            return out;
        }

        // Blockquote
        if (line.startsWith("> ")) {
            Component inner = parseInline(line.substring(2));
            out.add(Component.text("│ ", NamedTextColor.DARK_GRAY)
                    .append(inner.colorIfAbsent(NamedTextColor.GRAY)
                            .decorate(TextDecoration.ITALIC)));
            return out;
        }

        // Bullet list
        if (line.matches("^[\\-*] .+")) {
            out.add(Component.text("• ", NamedTextColor.GRAY)
                    .append(parseInline(line.substring(2))));
            return out;
        }

        // Numbered list: "1. " "12. " etc.
        if (line.matches("^\\d+\\. .+")) {
            int dot = line.indexOf(". ");
            out.add(Component.text(line.substring(0, dot + 2), NamedTextColor.GRAY)
                    .append(parseInline(line.substring(dot + 2))));
            return out;
        }

        // Blank line — skip
        if (line.isBlank()) return out;

        // Default: inline parsing, split long lines
        String remaining = line;
        while (remaining.length() > MAX_LINE) {
            out.add(parseInline(remaining.substring(0, MAX_LINE)));
            remaining = remaining.substring(MAX_LINE);
        }
        if (!remaining.isEmpty()) out.add(parseInline(remaining));
        return out;
    }

    /** Wraps inline(text) with a base color and decoration applied to the whole result. */
    private static Component inline(String text, NamedTextColor color, TextDecoration deco) {
        return parseInline(text).colorIfAbsent(color).decorate(deco);
    }

    /**
     * Parses inline markdown: **bold**, *italic*, `code`.
     * Handles flat (non-nested) markers; unmatched markers are treated as literals.
     */
    static Component parseInline(String text) {
        TextComponent.Builder b = Component.text();
        int i = 0;
        while (i < text.length()) {

            // **bold**
            if (startsWith(text, i, "**")) {
                int close = text.indexOf("**", i + 2);
                if (close > i + 2) {
                    b.append(Component.text(text.substring(i + 2, close))
                            .decorate(TextDecoration.BOLD));
                    i = close + 2;
                    continue;
                }
            }

            // *italic*
            if (text.charAt(i) == '*') {
                int close = text.indexOf('*', i + 1);
                if (close > i) {
                    b.append(Component.text(text.substring(i + 1, close))
                            .decorate(TextDecoration.ITALIC));
                    i = close + 1;
                    continue;
                }
            }

            // `code`
            if (text.charAt(i) == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i) {
                    b.append(Component.text(text.substring(i + 1, close),
                            NamedTextColor.YELLOW));
                    i = close + 1;
                    continue;
                }
            }

            // Plain text — consume until the next special character
            int next = text.length();
            for (int j = i + 1; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '*' || c == '`') { next = j; break; }
            }
            b.append(Component.text(text.substring(i, next)));
            i = next;
        }
        return b.build();
    }

    private static boolean startsWith(String s, int i, String prefix) {
        return s.regionMatches(i, prefix, 0, prefix.length());
    }
}

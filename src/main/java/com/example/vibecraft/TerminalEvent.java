package com.example.vibecraft;

import net.kyori.adventure.text.Component;
import java.util.List;

public sealed interface TerminalEvent permits
        TerminalEvent.StreamStart, TerminalEvent.StreamEnd,
        TerminalEvent.Thinking, TerminalEvent.ClaudeText,
        TerminalEvent.ToolCall, TerminalEvent.BashOutput,
        TerminalEvent.Question {

    record StreamStart() implements TerminalEvent {}
    record StreamEnd() implements TerminalEvent {}
    record Thinking(String text) implements TerminalEvent {}
    record ClaudeText(List<Component> lines, String rawText) implements TerminalEvent {}
    record ToolCall(String toolName, String detail) implements TerminalEvent {}
    record BashOutput(List<String> lines) implements TerminalEvent {}
    record Question(String prompt, List<String> options) implements TerminalEvent {}
}

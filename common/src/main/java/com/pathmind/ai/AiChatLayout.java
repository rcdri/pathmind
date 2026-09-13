package com.pathmind.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Testable, lossless chat wrapping and collapsible detail groups. */
public final class AiChatLayout {
    public enum Kind { HEADER, TEXT, DETAILS, SPACE }
    public record Row(String text, int entry, int offset, Kind kind, AiChatHistoryStore.Role role) { }
    private AiChatLayout() { }
    public static List<Row> layout(List<AiChatHistoryStore.Entry> entries, int width, ToIntFunction<String> measure, Set<Integer> expanded) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.role() == AiChatHistoryStore.Role.DETAIL) {
                int end = i + 1;
                while (end < entries.size() && entries.get(end).role() == AiChatHistoryStore.Role.DETAIL) end++;
                rows.add(new Row((expanded.contains(i) ? "▾ " : "▸ ") + "Details (" + (end - i) + ")", i, -1, Kind.DETAILS, entry.role()));
                if (expanded.contains(i)) for (int j = i; j < end; j++) wrap(rows, entries.get(j).text(), j, entry.role(), width, measure);
                i = end - 1; continue;
            }
            rows.add(new Row(switch (entry.role()) { case USER -> "You"; case ASSISTANT -> "Pathmind"; case ERROR -> "Error"; default -> "Update"; },
                i, -1, Kind.HEADER, entry.role()));
            wrap(rows, entry.text(), i, entry.role(), width, measure);
            rows.add(new Row("", i, Integer.MAX_VALUE, Kind.SPACE, entry.role()));
        }
        return List.copyOf(rows);
    }
    private static void wrap(List<Row> rows, String text, int entry, AiChatHistoryStore.Role role, int width, ToIntFunction<String> measure) {
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start), hardEnd = newline < 0 ? text.length() : newline;
            if (start == hardEnd) { rows.add(new Row("", entry, start, Kind.TEXT, role)); start++; continue; }
            int end = start;
            while (end < hardEnd) {
                int next = end + Character.charCount(text.codePointAt(end));
                if (measure.applyAsInt(text.substring(start, next)) > Math.max(1, width)) break;
                end = next;
            }
            if (end == start) end += Character.charCount(text.codePointAt(start));
            if (end < hardEnd) {
                int word = end;
                while (word > start && !Character.isWhitespace(text.charAt(word - 1))) word--;
                if (word > start) end = word;
            }
            rows.add(new Row(text.substring(start, end), entry, start, Kind.TEXT, role));
            start = end;
            if (start == hardEnd && newline >= 0) start++;
        }
    }
}

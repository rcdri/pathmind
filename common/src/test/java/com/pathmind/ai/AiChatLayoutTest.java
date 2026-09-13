package com.pathmind.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiChatLayoutTest {
    private static AiChatHistoryStore.Entry entry(AiChatHistoryStore.Role role, String text) { return new AiChatHistoryStore.Entry(role, text, 0); }
    private static List<AiChatLayout.Row> layout(List<AiChatHistoryStore.Entry> entries, int width, Set<Integer> expanded) {
        return AiChatLayout.layout(entries, width, String::length, expanded);
    }
    @Test void preservesLongMessagesParagraphsListsAndIndentation() {
        String text = "First paragraph.\n\n1. First step\n   - Nested item\n2. Second step\n" + "long-word".repeat(100);
        var rows = layout(List.of(entry(AiChatHistoryStore.Role.ASSISTANT, text)), 20, Set.of());
        var textRows = rows.stream().filter(row -> row.kind() == AiChatLayout.Kind.TEXT).toList();
        assertTrue(textRows.stream().anyMatch(row -> row.text().isEmpty()));
        assertTrue(textRows.stream().anyMatch(row -> row.text().startsWith("   -")));
        assertEquals(text.replace("\n", ""), textRows.stream().map(AiChatLayout.Row::text).collect(java.util.stream.Collectors.joining()));
        assertFalse(textRows.stream().anyMatch(row -> row.text().contains("…")));
    }
    @Test void detailsCollapseWithoutHidingConversationAndExpandLosslessly() {
        var entries = List.of(entry(AiChatHistoryStore.Role.USER, "Request"), entry(AiChatHistoryStore.Role.DETAIL, "Tool one"),
            entry(AiChatHistoryStore.Role.DETAIL, "Tool two"), entry(AiChatHistoryStore.Role.ASSISTANT, "Complete answer"));
        var collapsed = layout(entries, 30, Set.of());
        assertEquals(1, collapsed.stream().filter(row -> row.kind() == AiChatLayout.Kind.DETAILS).count());
        assertTrue(collapsed.stream().anyMatch(row -> row.text().equals("Complete answer")));
        assertFalse(collapsed.stream().anyMatch(row -> row.text().equals("Tool one")));
        var expanded = layout(entries, 30, Set.of(1));
        assertTrue(expanded.stream().anyMatch(row -> row.text().equals("Tool one")));
        assertTrue(expanded.stream().anyMatch(row -> row.text().equals("Tool two")));
    }
    @Test void resizeAndIncomingMessagesPreserveReadingAnchorUnlessFollowingEnd() {
        var entries = new ArrayList<>(List.of(entry(AiChatHistoryStore.Role.ASSISTANT, "word ".repeat(200))));
        var old = layout(entries, 20, Set.of());
        var scroll = new AiChatScrollState(); scroll.update(old, 6); scroll.seek(12);
        var anchor = old.get(scroll.top());
        var resized = layout(entries, 10, Set.of()); scroll.update(resized, 8);
        var newAnchor = resized.get(scroll.top());
        assertEquals(anchor.entry(), newAnchor.entry());
        assertTrue(newAnchor.offset() <= anchor.offset());
        int top = scroll.top();
        entries.add(entry(AiChatHistoryStore.Role.USER, "Another message"));
        scroll.update(layout(entries, 10, Set.of()), 8);
        assertEquals(top, scroll.top());
        assertFalse(scroll.atEnd());
        scroll.end(); assertEquals(scroll.max(), scroll.top());
        scroll.update(layout(entries, 40, Set.of()), 20); assertEquals(scroll.max(), scroll.top());
    }
    @Test void emptyChatAndCollapsedAnchorsStayInBounds() {
        var entries = List.of(entry(AiChatHistoryStore.Role.DETAIL, "detail ".repeat(100)), entry(AiChatHistoryStore.Role.ASSISTANT, "Answer"));
        var scroll = new AiChatScrollState(); scroll.update(layout(entries, 10, Set.of(0)), 4); scroll.seek(20);
        scroll.update(layout(entries, 10, Set.of()), 4);
        assertTrue(scroll.top() >= 0 && scroll.top() <= scroll.max());
        scroll.reset(); scroll.update(List.of(), 5); assertEquals(0, scroll.top());
        scroll.scroll(-100); assertEquals(0, scroll.top());
    }
    @Test void wrappingNeverSplitsUnicodeSurrogatePairs() {
        var rows = AiChatLayout.layout(List.of(entry(AiChatHistoryStore.Role.ASSISTANT, "😀😀😀")), 1,
            text -> text.codePointCount(0, text.length()), Set.of());
        assertEquals(3, rows.stream().filter(row -> row.kind() == AiChatLayout.Kind.TEXT && row.text().equals("😀")).count());
    }
}

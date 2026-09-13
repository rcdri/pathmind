package com.pathmind.ai;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiChatHistoryStoreTest {
    @TempDir Path directory;

    @Test void userClarificationsSurviveLongAssistantRepliesWithoutSendingAnotherProvidersHistory() {
        var store = new AiChatHistoryStore(directory.resolve("clarifications.json"));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "Use five blocks, not five seconds. Save my current position and return there.");
        store.append(AiProviderType.GEMINI, AiChatHistoryStore.Role.USER, "private other-provider answer");
        for (int i = 0; i < 5; i++) store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT,
            "Long explanation " + i + " " + "x".repeat(7500));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "Yes, continue with that.");
        String text = store.context(AiProviderType.OPENAI);
        var context = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(text.length() <= 24000);
        assertTrue(context.getAsJsonArray("olderUserMessages").toString().contains("five blocks, not five seconds"));
        assertTrue(context.getAsJsonArray("messages").toString().contains("Yes, continue"));
        assertFalse(text.contains("private other-provider answer"));
        assertEquals(text, new AiChatHistoryStore(directory.resolve("clarifications.json")).context(AiProviderType.OPENAI));
        store.reset(AiProviderType.OPENAI);
        assertFalse(store.context(AiProviderType.OPENAI).contains("five blocks"));
    }

    @Test void olderUserAnswersKeepTheirOrderSoCorrectionsCanSupersedeEarlierChoices() {
        var store = new AiChatHistoryStore(directory.resolve("corrections.json"));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "Walk three blocks.");
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "Actually use five blocks.");
        for (int i = 0; i < 5; i++) store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, "x".repeat(7500));
        var answers = JsonParser.parseString(store.context(AiProviderType.OPENAI)).getAsJsonObject().getAsJsonArray("olderUserMessages");
        assertEquals(2, answers.size());
        assertEquals("Walk three blocks.", answers.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("Actually use five blocks.", answers.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test void completeLongFormattedMessagesSurviveReload() {
        Path file = directory.resolve("formatted.json");
        String message = "Plan\n\n- First step\n  - Nested step\n\n" + "Complete response. ".repeat(200);
        var store = new AiChatHistoryStore(file);
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, message);
        assertEquals(message, new AiChatHistoryStore(file).history(AiProviderType.OPENAI).getFirst().text());
    }

    @Test void survivesReloadAndResetOnlyRemovesSelectedProvider() throws Exception {
        Path file = directory.resolve("history.json");
        var store = new AiChatHistoryStore(file);
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "jump five times");
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.EVENT, "Proposal discarded; NOT applied.");
        store.append(AiProviderType.GEMINI, AiChatHistoryStore.Role.USER, "walk north");
        var restored = new AiChatHistoryStore(file);
        assertEquals(2, restored.history(AiProviderType.OPENAI).size());
        assertTrue(restored.context(AiProviderType.OPENAI).contains("NOT applied"));
        assertFalse(restored.context(AiProviderType.OPENAI).contains("walk north"));
        restored.reset(AiProviderType.OPENAI);
        var again = new AiChatHistoryStore(file);
        assertTrue(again.history(AiProviderType.OPENAI).isEmpty());
        assertEquals(1, again.history(AiProviderType.GEMINI).size());
    }

    @Test void contextIsBoundedWhileFullArchiveRemainsAndDetailsAreNotSent() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.DETAIL, "raw field edits are display only");
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ERROR, "network diagnostic");
        for (int i = 0; i < 40; i++) store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "message-" + i + " " + "x".repeat(1000));
        String context = store.context(AiProviderType.OPENAI);
        assertTrue(context.length() <= 24000);
        assertTrue(context.contains("message-39"));
        assertFalse(context.contains("message-0 "));
        assertFalse(context.contains("raw field edits"));
        assertFalse(context.contains("network diagnostic"));
        assertTrue(JsonParser.parseString(context).getAsJsonObject().get("olderEntriesOmitted").getAsInt() > 0);
        assertEquals(42, new AiChatHistoryStore(directory.resolve("history.json")).history(AiProviderType.OPENAI).size());
    }

    @Test void unreadableArchiveIsNotSilentlyOverwrittenAndResetPreservesBackup() throws Exception {
        Path file = directory.resolve("history.json");
        Files.writeString(file, "broken original");
        var store = new AiChatHistoryStore(file);
        assertFalse(store.warning().isBlank());
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "new message");
        assertEquals("broken original", Files.readString(file));
        store.reset(AiProviderType.OPENAI);
        assertTrue(store.warning().isBlank());
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("history.json.unreadable-")));
        }
        assertTrue(new AiChatHistoryStore(file).history(AiProviderType.OPENAI).isEmpty());
    }

    @Test void sameDirectorySharesHistoryAcrossEditorControllers() {
        var first = AiChatHistoryStore.open(directory);
        var second = AiChatHistoryStore.open(directory);
        assertSame(first, second);
        first.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, "ready");
        assertEquals(1, second.history(AiProviderType.OPENAI).size());
        second.reset(AiProviderType.OPENAI);
        assertTrue(first.history(AiProviderType.OPENAI).isEmpty());
        assertEquals(1, first.generation(AiProviderType.OPENAI));
        assertEquals(0, first.generation(AiProviderType.GEMINI));
        assertEquals(2, first.revision(AiProviderType.OPENAI));
    }
}

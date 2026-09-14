package com.pathmind.ai;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestStampTest {
    @TempDir Path directory;
    @Test void resetRejectsLateSuccessWithoutResurrectingHistory() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        var stamp = AiRequestStamp.capture(AiProviderType.OPENAI, 4, store);
        var pending = new CompletableFuture<String>();
        boolean[] presented = {false};
        pending.thenAccept(response -> {
            if (!stamp.isCurrent(AiProviderType.OPENAI, 4, store)) return;
            presented[0] = true;
            store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, response);
        });
        store.reset(AiProviderType.OPENAI); pending.complete("Old response");
        assertFalse(presented[0]); assertTrue(store.history(AiProviderType.OPENAI).isEmpty());
    }
    @Test void cancellationAndSwitchAwayAndBackCannotRevalidateOldCallback() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        var stamp = AiRequestStamp.capture(AiProviderType.OPENAI, 2, store);
        assertTrue(stamp.isCurrent(AiProviderType.OPENAI, 2, store));
        assertFalse(stamp.isCurrent(AiProviderType.GEMINI, 2, store));
        assertFalse(stamp.isCurrent(AiProviderType.OPENAI, 3, store));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.DETAIL, "Progress does not invalidate request");
        assertTrue(stamp.isCurrent(AiProviderType.OPENAI, 2, store));
    }
}

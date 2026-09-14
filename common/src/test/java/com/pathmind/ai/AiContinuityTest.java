package com.pathmind.ai;

import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiContinuityTest {
    @TempDir Path directory;

    @Test void goalsAndDecisionsSurviveLongHistoryReloadWhileResetPreservesExplicitPreferences() {
        var file = directory.resolve("history.json");
        var store = new AiChatHistoryStore(file);
        store.savePreferences("Prefer reusable routines.\nDo not add inventory steps unless requested.");
        var summary = new AiConversationSummary("Return to the saved starting position", List.of("User wants pathfinding for the return"), List.of("Decide what to do if no path exists"));
        store.saveSummary(AiProviderType.OPENAI, summary);
        for (int i = 0; i < 40; i++) store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.USER, "x".repeat(1000));
        var restored = new AiChatHistoryStore(file);
        assertEquals(summary, restored.summary(AiProviderType.OPENAI));
        assertEquals(store.preferences(), restored.preferences());
        assertTrue(restored.context(AiProviderType.OPENAI).contains(summary.goal()));
        assertTrue(restored.context(AiProviderType.OPENAI).length() <= 24000);
        restored.reset(AiProviderType.OPENAI);
        var reset = new AiChatHistoryStore(file);
        assertNull(reset.summary(AiProviderType.OPENAI));
        assertFalse(reset.preferences().isBlank());
        assertTrue(reset.history(AiProviderType.OPENAI).isEmpty());
        reset.savePreferences(""); assertEquals("", new AiChatHistoryStore(file).preferences());
    }

    @Test void nullGoalPreservesGoalWhileUpdatingResolvedDecisionsAndQuestions() {
        var store = new AiChatHistoryStore(directory.resolve("merge.json"));
        store.saveSummary(AiProviderType.OPENAI, new AiConversationSummary("Build a return path",
            List.of("Distance is unresolved"), List.of("Ask whether distance means blocks or seconds")));
        var action = JsonParser.parseString("""
            {"continuityGoal":null,"continuityDecisions":["Use five blocks"],"continuityUnfinished":[]}
            """).getAsJsonObject();
        store.saveSummary(AiProviderType.OPENAI, AiConversationSummary.fromAction(action));
        assertEquals("Build a return path", store.summary(AiProviderType.OPENAI).goal());
        assertEquals(List.of("Use five blocks"), store.summary(AiProviderType.OPENAI).decisions());
        assertTrue(store.summary(AiProviderType.OPENAI).unfinished().isEmpty());
    }

    @Test void appliedAndDiscardedReceiptsAreNotInferredFromAssistantClaims() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, "I applied this already");
        store.recordChange(AiProviderType.OPENAI, "PROPOSED", "Test", "draft");
        store.recordChange(AiProviderType.OPENAI, "DISCARDED", "Test", "draft");
        store.recordChange(AiProviderType.OPENAI, "APPLIED", "Different", "accepted", "+ node JUMP; + connection");
        var capsule = JsonParser.parseString(new AiChatHistoryStore(directory.resolve("history.json")).context(AiProviderType.OPENAI)).getAsJsonObject().getAsJsonObject("continuity");
        var records = capsule.getAsJsonArray("changeRecords");
        assertEquals(3, records.size());
        assertTrue(records.get(0).getAsString().startsWith("PROPOSED:"));
        assertTrue(records.get(1).getAsString().startsWith("DISCARDED:"));
        assertTrue(records.get(2).getAsString().contains("fingerprint=accepted"));
        assertTrue(records.get(2).getAsString().contains("changes=+ node JUMP"));
        assertFalse(store.context(AiProviderType.GEMINI).contains("fingerprint=accepted"));
    }

    @Test void workspaceSelectionIsFreshFilteredAndDeterministicRatherThanRememberedFromChat() {
        var node = new NodeGraphData.NodeData("current-jump", NodeType.JUMP, null, 0, 0, new ArrayList<>());
        var graph = new NodeGraphData(new ArrayList<>(List.of(node)), new ArrayList<>());
        String context = AiWorkspaceContext.attach("{\"summary\":\"An old WALK graph\"}", "Current", graph, List.of("deleted-node", "current-jump", "current-jump"));
        var workspace = JsonParser.parseString(context).getAsJsonObject().getAsJsonObject("workspace");
        assertEquals("Current", workspace.get("presetName").getAsString());
        assertEquals(1, workspace.get("nodeCount").getAsInt());
        assertEquals(1, workspace.getAsJsonArray("selectedNodeIds").size());
        assertEquals("current-jump", workspace.getAsJsonArray("selectedNodeIds").get(0).getAsString());
        assertEquals(AiPresetService.graphFingerprint("Current", graph), workspace.get("fingerprint").getAsString());
    }

    @Test void finishReturnsSummaryWithoutPromotingPreferencesOrGrantingEditPermission() {
        var control = new AiRequestControl();
        AiProvider provider = request -> CompletableFuture.completedFuture("""
            {"tool":"finish","target":"undecided","requestIntent":"discuss","intentEvidence":"What do you think?",
             "response":"Let's keep the routine reusable.","continuityGoal":"Discuss a reusable routine",
             "continuityDecisions":[],"continuityUnfinished":["User has not approved implementation"]}
            """);
        var report = AiPresetAgent.runMeasured(provider, "test", "What do you think?", "", null, "", true, true, control).join();
        assertTrue(report.succeeded()); assertFalse(report.proposal().changesGraph());
        assertEquals(AiCompletionOutcome.ANSWER, report.proposal().outcome());
        assertEquals("Discuss a reusable routine", control.summary().goal());
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        store.saveSummary(AiProviderType.OPENAI, control.summary());
        assertTrue(store.preferences().isBlank());
        assertThrows(IllegalStateException.class, () -> store.savePreferences("x".repeat(2001)));
        assertThrows(IllegalArgumentException.class, () -> new AiConversationSummary("x".repeat(801), List.of(), List.of()));
    }

    @Test void clearingSummaryCannotOverwriteAnUnreadableArchive() throws Exception {
        var file = directory.resolve("broken.json");
        java.nio.file.Files.writeString(file, "original unreadable content");
        var store = new AiChatHistoryStore(file);
        assertThrows(IllegalStateException.class, () -> store.clearSummary(AiProviderType.OPENAI));
        assertThrows(IllegalStateException.class, () -> store.savePreferences("New preference"));
        assertEquals("original unreadable content", java.nio.file.Files.readString(file));
    }

    @Test void oversizedSummaryLeavesPreviousNotesIntact() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        var previous = new AiConversationSummary("Keep this goal", List.of(), List.of());
        store.saveSummary(AiProviderType.OPENAI, previous);
        var oversized = new AiConversationSummary("Goal", java.util.Collections.nCopies(8, "<".repeat(800)), List.of());
        assertThrows(IllegalStateException.class, () -> store.saveSummary(AiProviderType.OPENAI, oversized));
        assertEquals(previous, store.summary(AiProviderType.OPENAI));
    }
}

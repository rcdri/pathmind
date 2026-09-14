package com.pathmind.ai;

import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiContinuityTest {
    @TempDir Path directory;

    @Test void legacySummaryAndPreferencesArePurgedWhenHistoryLoads() throws Exception {
        var file = directory.resolve("history.json");
        java.nio.file.Files.writeString(file, """
            {"version":1,"providers":{},"preferences":"Old preference",
             "summaries":{"openai":{"goal":"Old goal","decisions":[],"unfinished":[]}},"changeRecords":{}}
            """);
        var store = new AiChatHistoryStore(file);
        var saved = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        assertFalse(saved.has("preferences"));
        assertFalse(saved.has("summaries"));
        assertTrue(store.history(AiProviderType.OPENAI).isEmpty());
    }

    @Test void appliedAndDiscardedReceiptsAreNotInferredFromAssistantClaims() {
        var store = new AiChatHistoryStore(directory.resolve("history.json"));
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, "I applied this already");
        store.recordChange(AiProviderType.OPENAI, "PROPOSED", "Test", "draft");
        store.recordChange(AiProviderType.OPENAI, "DISCARDED", "Test", "draft");
        store.recordChange(AiProviderType.OPENAI, "APPLIED", "Different", "accepted", "+ node JUMP; + connection");
        var capsule = JsonParser.parseString(new AiChatHistoryStore(directory.resolve("history.json")).context(AiProviderType.OPENAI)).getAsJsonObject().getAsJsonObject("changeHistory");
        var records = capsule.getAsJsonArray("records");
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

}

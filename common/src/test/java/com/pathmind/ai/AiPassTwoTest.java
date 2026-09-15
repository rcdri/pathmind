package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AiPassTwoTest {
    @TempDir Path directory;
    private static JsonArray array(String value) { return JsonParser.parseString(value).getAsJsonArray(); }
    private static JsonObject empty() { return JsonParser.parseString("{\"nodes\":[],\"connections\":[],\"routines\":[]}").getAsJsonObject(); }
    private static AiGraphCommandEngine.Result routine() {
        return AiGraphCommandEngine.apply(empty(), array("""
            [{"kind":"add_sequence","refs":["start","wait","after"],"nodeTypes":["START","WAIT","MESSAGE"]},
             {"kind":"create_routine","refs":["wait"],"ref":"call","routineRef":"work","name":"Work","routineInputs":[]}]
            """), Map.of(), true, true);
    }
    @Test void planCorrectionCanRepairBindingsButCannotWeakenBehavior() {
        JsonObject before = JsonParser.parseString("""
            {"requirements":[{"ref":"wrong","nodeType":"CRAFT","parameterId":"amount","value":"8"}],
             "structuralRequirements":[{"kind":"parameter","ref":"wrong","toRef":"source","slotIndex":1}]}
            """).getAsJsonObject();
        JsonObject corrected = JsonParser.parseString("""
            {"requirements":[{"ref":"craft","nodeType":"CRAFT","parameterId":"amount","value":"8"}],
             "structuralRequirements":[{"kind":"parameter","ref":"craft","toRef":"amount","slotIndex":0}]}
            """).getAsJsonObject();
        JsonObject weakened = JsonParser.parseString("""
            {"requirements":[],"structuralRequirements":[]}
            """).getAsJsonObject();
        assertTrue(AiPlanCorrection.preservesBehavior(before, corrected));
        assertFalse(AiPlanCorrection.preservesBehavior(before, weakened));
    }

    @Test void recurringValidationFailuresSurviveDraftRevisionChanges() {
        AiProgressTracker tracker = new AiProgressTracker();
        JsonObject failed = JsonParser.parseString("""
            {"ok":false,"message":"repair","issues":[{"code":"same","message":"same issue"}],"draftRevision":1}
            """).getAsJsonObject();
        assertEquals(0, tracker.record("validate_graph", failed, 1));
        failed.addProperty("draftRevision", 2);
        assertEquals(1, tracker.record("validate_graph", failed, 2));
        failed.addProperty("message", "cosmetic wording");
        failed.addProperty("draftRevision", 3);
        assertEquals(2, tracker.record("validate_graph", failed, 3));
    }
    @Test void editsExtractedRoutineWithoutTouchingRootAndKeepsBodyReferences() {
        var created = routine(); assertTrue(created.success(), created.message());
        assertTrue(created.references().containsKey("wait"));
        var edited = AiGraphCommandEngine.apply(created.graph(), array("""
            [{"kind":"configure_node","graphRef":"work","ref":"wait","mode":null,"parameterValues":[{"parameterId":"duration","value":"9"}]}]
            """), created.references(), true, true);
        assertTrue(edited.success(), edited.message());
        assertEquals(created.graph().get("nodes"), edited.graph().get("nodes"));
        var graph = NodeGraphPersistence.parseNodeGraphData(edited.graph().toString());
        var body = AiGraphScope.resolve(graph, "work", edited.references());
        var query = AiGraphQueryEngine.find(new com.google.gson.Gson().toJsonTree(body).getAsJsonObject(), array("[\"WAIT\"]"), null, edited.references());
        assertTrue(query.toString().contains("\"duration\":\"9\""));
        assertTrue(edited.actualValues().toString().contains("\"value\":\"9\""));
    }
    @Test void crossScopeConnectionRejectsWholeBatch() {
        var created = routine();
        var rejected = AiGraphCommandEngine.apply(created.graph(), array("""
            [{"kind":"set_parameter","graphRef":"work","ref":"wait","parameterId":"duration","value":"9"},
             {"kind":"connect","graphRef":"work","from":"start","to":"wait","outputSocket":0,"inputSocket":0}]
            """), created.references(), true, true);
        assertFalse(rejected.success());
        assertFalse(created.graph().toString().contains("\"value\":\"9\""));
    }
    @Test void structuralChecksRejectWrongOrderAndSupportRoutineScope() {
        var created = routine(); var graph = NodeGraphPersistence.parseNodeGraphData(created.graph().toString());
        var correct = array("""
            [{"kind":"node","graphRef":"work","ref":"wait","nodeType":"WAIT"},
             {"kind":"flow","ref":"start","toRef":"call","outputSocket":0,"inputSocket":0}]
            """);
        AiStructuralRequirements.checkShape(correct);
        assertTrue(AiStructuralRequirements.verify(graph, correct, created.references()).isEmpty());
        var wrong = array("[{\"kind\":\"flow\",\"ref\":\"call\",\"toRef\":\"start\",\"outputSocket\":0,\"inputSocket\":0}]");
        assertEquals("structural_requirement_mismatch", AiStructuralRequirements.verify(graph, wrong, created.references())
            .get(0).getAsJsonObject().get("code").getAsString());
        assertThrows(IllegalArgumentException.class, () -> AiStructuralRequirements.checkShape(array("[{\"kind\":\"flow\",\"ref\":\"start\"}]")));
    }
    @Test void progressDetectsAlternatingCyclesAndIgnoresCosmeticMessages() {
        var tracker = new AiProgressTracker();
        JsonObject read = JsonParser.parseString("{\"ok\":true,\"nodes\":[]}").getAsJsonObject();
        JsonObject failure = JsonParser.parseString("{\"ok\":false,\"code\":\"unknown_instance_parameter\"}").getAsJsonObject();
        tracker.record("inspect_subgraph", read, 0); tracker.record("apply_graph_commands", failure, 0);
        int stale = 0;
        for (int i = 0; i < 12; i++) {
            var payload = (i % 2 == 0 ? read : failure).deepCopy(); payload.addProperty("message", "wording " + i);
            stale = tracker.record(i % 2 == 0 ? "inspect_subgraph" : "apply_graph_commands", payload, 0);
        }
        assertEquals(12, stale);
        assertEquals(0, tracker.record("inspect_subgraph", read, 1));
    }
    @Test void saveFailureRestoresPreviousGraphAndRollbackFailureIsExplicit() {
        NodeGraphData previous = new NodeGraphData(), proposed = new NodeGraphData();
        var editor = new AtomicReference<>(previous);
        assertEquals(AiApplyTransaction.Outcome.REVERTED, AiApplyTransaction.apply(previous, proposed, value -> { editor.set(value); return true; }, () -> false));
        assertSame(previous, editor.get());
        assertEquals(AiApplyTransaction.Outcome.ROLLBACK_FAILED, AiApplyTransaction.apply(previous, proposed, value -> value == proposed, () -> false));
        assertEquals(AiApplyTransaction.Outcome.SAVED, AiApplyTransaction.apply(previous, proposed, value -> true, () -> true));
    }
    @Test void detailBatchFlushesWithFinalReplyAndResetCannotResurrectIt() throws Exception {
        Path file = directory.resolve("history.json"); var store = new AiChatHistoryStore(file);
        for (int i = 0; i < 40; i++) store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.DETAIL, "detail " + i);
        store.append(AiProviderType.OPENAI, AiChatHistoryStore.Role.ASSISTANT, "Ready for review.");
        assertEquals(41, new AiChatHistoryStore(file).history(AiProviderType.OPENAI).size());
        store.reset(AiProviderType.OPENAI); store.flush();
        assertTrue(new AiChatHistoryStore(file).history(AiProviderType.OPENAI).isEmpty());
        assertTrue(Files.exists(file));
    }
    @Test void failedReplacementPreservesDestinationAndCleansTemporaryFile() throws Exception {
        Path destination = Files.createDirectory(directory.resolve("cannot-replace.json"));
        Path marker = destination.resolve("keep.txt"); Files.writeString(marker, "existing data");
        assertFalse(NodeGraphPersistence.saveNodeGraphDataToPath(NodeGraphPersistence.parseNodeGraphData(empty().toString()), destination));
        assertEquals("existing data", Files.readString(marker));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("pathmind-preset-")));
        }
    }
}

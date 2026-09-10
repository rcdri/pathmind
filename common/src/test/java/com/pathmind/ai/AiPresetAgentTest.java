package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPresetAgentTest {
    @Test
    void actionSchemaRequiresANonNullTarget() {
        JsonObject target = AiAgentTurnSchema.create()
            .getAsJsonObject("properties").getAsJsonObject("target");

        assertEquals("string", target.get("type").getAsString());
        assertNotNull(target.getAsJsonArray("enum"));
    }

    @Test
    void inspectionUsesToolsAndReturnsNoGraph() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            action("inspect_preset", null, null, List.of()),
            action("finish", null, null, List.of())
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "What is wrong?", "",
            simpleGraph(), "Open", true, true).join();

        assertFalse(proposal.changesGraph());
        assertEquals(3, provider.requests.size());
        assertTrue(provider.requests.get(2).userPrompt().contains("activePreset"));
    }

    @Test
    void graphProposalMustValidateAndPreviewBeforeFinish() {
        NodeGraphData graph = simpleGraph();
        JsonArray operations = new JsonArray();
        for (NodeGraphData.NodeData node : graph.getNodes()) {
            operations.add(operation("add", "/nodes/-", new Gson().toJson(node)));
        }
        operations.add(operation("add", "/connections/-", new Gson().toJson(graph.getConnections().get(0))));
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of()),
            action("apply_graph_patch", null, operations, List.of()),
            action("finish", null, null, List.of()),
            action("validate_graph", null, null, List.of()),
            action("preview_execution", null, null, List.of()),
            action("finish", null, null, List.of("Validated the draft."))
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertTrue(proposal.changesGraph());
        assertEquals("new", proposal.target());
        assertEquals(2, proposal.graph().getNodes().size());
        assertEquals(6, provider.requests.size());
        assertTrue(provider.requests.get(3).userPrompt().contains("must pass validate_graph"));
    }

    @Test
    void promptFallbackDependsOnCapabilityRatherThanProviderName() {
        FakeProvider textOnly = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            action("inspect_preset", null, null, List.of()),
            action("finish", null, null, List.of())
        );
        FakeProvider structured = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            action("inspect_preset", null, null, List.of()),
            action("finish", null, null, List.of())
        ) {
            @Override
            public AiProviderCapabilities capabilities() {
                return AiProviderCapabilities.STRUCTURED_OUTPUT;
            }
        };

        AiPresetAgent.run(textOnly, "model", "Inspect", "", simpleGraph(), "Open", true, true).join();
        AiPresetAgent.run(structured, "model", "Inspect", "", simpleGraph(), "Open", true, true).join();

        assertTrue(textOnly.requests.get(0).systemPrompt().contains("This transport cannot enforce the schema"));
        assertFalse(structured.requests.get(0).systemPrompt().contains("This transport cannot enforce the schema"));
        assertEquals(textOnly.requests.get(0).outputSchema(), structured.requests.get(0).outputSchema());
    }

    @Test
    void staleDraftRevisionIsRejectedAndReturnedToTheModel() {
        NodeGraphData graph = simpleGraph();
        JsonArray start = new JsonArray();
        start.add(operation("add", "/nodes/-", new Gson().toJson(graph.getNodes().get(0))));
        JsonArray jump = new JsonArray();
        jump.add(operation("add", "/nodes/-", new Gson().toJson(graph.getNodes().get(1))));
        JsonArray connection = new JsonArray();
        connection.add(operation("add", "/connections/-", new Gson().toJson(graph.getConnections().get(0))));
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of(), 0),
            action("apply_graph_patch", null, start, List.of(), 0),
            action("apply_graph_patch", null, jump, List.of(), 0),
            action("apply_graph_patch", null, jump, List.of(), 1),
            action("apply_graph_patch", null, connection, List.of(), 2),
            action("validate_graph", null, null, List.of(), 3),
            action("preview_execution", null, null, List.of(), 3),
            action("finish", null, null, List.of(), 3)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(2, proposal.graph().getNodes().size());
        assertTrue(provider.requests.get(3).userPrompt().contains("Draft revision mismatch"));
        assertTrue(provider.requests.get(3).userPrompt().contains("\"draftRevision\":1"));
    }

    @Test
    void nodeContractsAreBatchedAndIncludeOnlyRelevantExamples() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            describeAction("START", "CREATE_LIST"),
            action("inspect_preset", null, null, List.of()),
            action("finish", null, null, List.of())
        );

        AiPresetAgent.run(provider, "model", "Explain these nodes", "", simpleGraph(), "Open", true, true).join();

        String transcript = provider.requests.get(2).userPrompt();
        assertTrue(transcript.contains("\"contracts\""));
        assertTrue(transcript.contains("\"relevantExamples\""));
        assertTrue(transcript.contains("onboarding-3"));
    }

    @Test
    void simpleCreationCanCompleteInFourProviderCalls() {
        NodeGraphData graph = simpleGraph();
        JsonArray operations = new JsonArray();
        graph.getNodes().forEach(node -> operations.add(operation("add", "/nodes/-", new Gson().toJson(node))));
        operations.add(operation("add", "/connections/-", new Gson().toJson(graph.getConnections().get(0))));
        FakeProvider provider = new FakeProvider(
            describeActionForTarget("new", "START", "JUMP"),
            action("apply_graph_patch", "new", operations, List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("finish", "new", null, List.of("Validated and previewed."), 1)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(4, provider.requests.size());
        assertTrue(proposal.review() != null && !proposal.review().executionPaths().isEmpty());
    }

    @Test
    void distinctRecoverableErrorsDoNotExhaustTheStagnationGuard() {
        NodeGraphData graph = simpleGraph();
        JsonArray operations = new JsonArray();
        graph.getNodes().forEach(node -> operations.add(operation("add", "/nodes/-", new Gson().toJson(node))));
        operations.add(operation("add", "/connections/-", new Gson().toJson(graph.getConnections().get(0))));
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of(), 0),
            action("not_a_tool", "new", null, List.of(), 0),
            action("apply_graph_patch", "new", operations, List.of(), 1),
            action("validate_graph", "new", null, List.of(), 0),
            action("apply_graph_patch", "new", operations, List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("finish", "new", null, List.of(), 1)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a complex preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(7, provider.requests.size());
        assertEquals(2, proposal.graph().getNodes().size());
    }

    private static String action(String tool, String target, JsonArray operations, List<String> workLog) {
        return action(tool, target, operations, workLog, 0);
    }

    private static String action(String tool, String target, JsonArray operations, List<String> workLog, int draftRevision) {
        JsonObject action = new JsonObject();
        action.addProperty("tool", tool);
        if (target == null) action.add("target", null); else action.addProperty("target", target);
        action.add("nodeTypes", new JsonArray());
        action.add("exampleId", null);
        action.addProperty("draftRevision", draftRevision);
        action.add("operations", operations == null ? new JsonArray() : operations);
        action.addProperty("title", "Jump preset");
        action.addProperty("response", "Prepared for review.");
        JsonArray log = new JsonArray();
        workLog.forEach(log::add);
        action.add("workLog", log);
        return action.toString();
    }

    private static JsonObject operation(String op, String path, String valueJson) {
        JsonObject operation = new JsonObject();
        operation.addProperty("op", op);
        operation.addProperty("path", path);
        operation.addProperty("valueJson", valueJson);
        return operation;
    }

    private static String describeAction(String... types) {
        return describeActionForTarget(null, types);
    }

    private static String describeActionForTarget(String target, String... types) {
        JsonObject action = new JsonObject();
        action.addProperty("tool", "describe_node_types");
        if (target == null) action.add("target", null); else action.addProperty("target", target);
        JsonArray nodeTypes = new JsonArray();
        for (String type : types) nodeTypes.add(type);
        action.add("nodeTypes", nodeTypes);
        action.add("exampleId", null);
        action.addProperty("draftRevision", 0);
        action.add("operations", new JsonArray());
        action.add("title", null);
        action.add("response", null);
        action.add("workLog", new JsonArray());
        return action.toString();
    }

    private static NodeGraphData simpleGraph() {
        NodeGraphData.NodeData start = new NodeGraphData.NodeData("start", NodeType.START, null, 0, 0, new ArrayList<>());
        NodeGraphData.NodeData jump = new NodeGraphData.NodeData("jump", NodeType.JUMP, null, 180, 0, new ArrayList<>());
        NodeGraphData graph = new NodeGraphData(new ArrayList<>(List.of(start, jump)),
            new ArrayList<>(List.of(new NodeGraphData.ConnectionData("start", "jump", 0, 0))));
        graph.setRoutines(new ArrayList<>());
        return graph;
    }

    private static class FakeProvider implements AiProvider {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<AiPresetRequest> requests = new ArrayList<>();

        private FakeProvider(String... responses) { this.responses.addAll(List.of(responses)); }

        @Override
        public CompletableFuture<String> generate(AiPresetRequest request) {
            requests.add(request);
            String response = responses.poll();
            if (response == null) return CompletableFuture.failedFuture(new AssertionError("Unexpected agent turn"));
            return CompletableFuture.completedFuture(response);
        }
    }
}

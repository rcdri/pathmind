package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    @Test void bindsAnExistingRoutineNodeAndVerifiesAScopedEdit() {
        var created = AiGraphCommandEngine.apply(JsonParser.parseString("{\"nodes\":[],\"connections\":[],\"routines\":[]}").getAsJsonObject(),
            JsonParser.parseString("""
                [{"kind":"add_sequence","refs":["start","wait","after"],"nodeTypes":["START","WAIT","JUMP"]},
                 {"kind":"create_routine","refs":["wait"],"ref":"call","routineRef":"work","name":"Work","routineInputs":[]}]
                """).getAsJsonArray(), java.util.Map.of(), true, true);
        assertTrue(created.success(), created.message());
        String routineId = created.references().get("work");
        var inspect = JsonParser.parseString(action("inspect_subgraph", "current", null, List.of())).getAsJsonObject();
        inspect.addProperty("graphRef", routineId); inspect.getAsJsonArray("nodeRefs").add(created.references().get("wait"));
        var bind = JsonParser.parseString(action("bind_node_ref", "current", null, List.of())).getAsJsonObject();
        bind.addProperty("graphRef", routineId); bind.addProperty("ref", "existing_wait"); bind.addProperty("nodeId", created.references().get("wait"));
        var plan = JsonParser.parseString(planAction("current", "Change the routine wait", "WAIT")).getAsJsonObject();
        var expected = requirement("existing_wait", "WAIT", "duration", "9"); expected.addProperty("graphRef", routineId);
        plan.getAsJsonArray("planRequirements").add(expected);
        var structure = JsonParser.parseString("{\"kind\":\"node\",\"ref\":\"existing_wait\",\"nodeType\":\"WAIT\"}").getAsJsonObject();
        structure.addProperty("graphRef", routineId); JsonArray structures = new JsonArray(); structures.add(structure); plan.add("structuralRequirements", structures);
        JsonObject edit = emptyCommand("set_parameter"); edit.addProperty("ref", "existing_wait"); edit.addProperty("parameterId", "duration");
        edit.addProperty("value", "9"); edit.addProperty("graphRef", routineId); JsonArray edits = new JsonArray(); edits.add(edit);
        var provider = new FakeProvider(inspect.toString(), bind.toString(), plan.toString(),
            action("apply_graph_commands", "current", edits, List.of(), 0), action("validate_graph", "current", null, List.of(), 1),
            action("finish", "current", null, List.of(), 1));
        var proposal = AiPresetAgent.run(provider, "model", "Change the wait in this routine to 9 seconds", "",
            com.pathmind.data.NodeGraphPersistence.parseNodeGraphData(created.graph().toString()), "Open", true, true).join();
        assertTrue(proposal.editsCurrentPreset());
        assertTrue(proposal.review().changes().stream().anyMatch(line -> line.contains("Duration=9")));
        assertTrue(proposal.graph().getRoutines().get(0).getGraph().getNodes().stream().anyMatch(n -> n.getParameters() != null
            && n.getParameters().stream().anyMatch(p -> "duration".equals(p.getId()) && "9".equals(p.getValue()))));
    }
    @Test void runtimeDependentRequirementsRemainVisibleWithoutBlockingReview() {
        JsonObject plan = JsonParser.parseString(planAction("new", "Build a runtime-configured craft", "START", "CRAFT")).getAsJsonObject();
        plan.getAsJsonArray("planRequirements").add(requirement("craft", "CRAFT", "Amount", "4"));
        JsonArray commands = new JsonArray();
        commands.add(addNode("start", "START")); commands.add(addNode("craft", "CRAFT"));
        commands.add(connect("start", "craft", 0, 0)); commands.add(addNode("random", "OPERATOR_RANDOM"));
        JsonObject attachment = emptyCommand("attach_parameter");
        attachment.addProperty("host", "craft"); attachment.addProperty("child", "random");
        attachment.addProperty("slotIndex", 0); commands.add(attachment);
        FakeProvider provider = new FakeProvider(plan.toString(), action("apply_graph_commands", "new", commands, List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1), action("finish", "new", null, List.of(), 1));
        var proposal = AiPresetAgent.run(provider, "model", "Build a runtime-configured craft", "", simpleGraph(), "Open", true, true).join();
        assertTrue(proposal.changesGraph());
        assertTrue(provider.requests.get(3).userPrompt().contains("requirement_runtime_dependent"));
        assertTrue(provider.requests.get(3).userPrompt().contains("\"severity\":\"warning\""));
        assertTrue(proposal.review().changes().stream().anyMatch(line -> line.contains("Amount=runtime-dependent")));
    }
    @Test void nativeAndFallbackProvidersReceiveTheSameBehavioralGuidance() {
        FakeProvider fallback = new FakeProvider(action("inspect_preset", "inspect", null, List.of()), action("finish", "inspect", null, List.of()));
        FakeProvider nativeProvider = new FakeProvider(action("inspect_preset", "inspect", null, List.of()), action("finish", "inspect", null, List.of())) {
            @Override public AiProviderCapabilities capabilities() { return AiProviderCapabilities.NATIVE_TOOLS; }
        };
        AiPresetAgent.run(fallback, "model", "Explain the idea", "", simpleGraph(), "Open", true, true).join();
        AiPresetAgent.run(nativeProvider, "model", "Explain the idea", "", simpleGraph(), "Open", true, true).join();
        for (String guidance : List.of("sourceNodeId", "planRequirements", "Internal validation", "Runtime-dependent requirements")) {
            assertTrue(fallback.requests.getFirst().systemPrompt().contains(guidance));
            assertTrue(nativeProvider.requests.getFirst().systemPrompt().contains(guidance));
        }
        assertFalse(nativeProvider.requests.getFirst().systemPrompt().contains("target is never null"));
    }
    @Test
    void actionSchemaRequiresANonNullTarget() {
        JsonObject properties = AiAgentTurnSchema.create().getAsJsonObject("properties");
        JsonObject target = properties.getAsJsonObject("target");

        assertEquals("string", target.get("type").getAsString());
        assertNotNull(target.getAsJsonArray("enum"));
        assertTrue(properties.has("commands"));
        assertTrue(properties.has("planSteps"));
        assertTrue(properties.has("exampleTraits"));
        assertFalse(properties.has("operations"));
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
        assertTrue(provider.requests.getFirst().systemPrompt().contains("Default to 1-3 short sentences"));
        assertTrue(provider.requests.getFirst().systemPrompt().contains("not graph commands"));
        assertTrue(provider.requests.get(2).userPrompt().contains("activePreset"));
    }

    @Test
    void graphProposalMustValidateAndPreviewBeforeFinish() {
        JsonArray commands = simpleGraphCommands();
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of()),
            planAction("new", "Build a valid jump sequence", "START", "JUMP"),
            action("apply_graph_commands", null, commands, List.of()),
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
        assertEquals(7, provider.requests.size());
        assertTrue(provider.requests.get(4).userPrompt().contains("must pass validate_graph"));
    }

    @Test
    void editingIsRejectedUntilAStructuralPlanExists() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of()),
            action("apply_graph_commands", "new", simpleGraphCommands(), List.of(), 0),
            planAction("new", "Build a valid jump sequence", "START", "JUMP"),
            action("apply_graph_commands", "new", simpleGraphCommands(), List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("finish", "new", null, List.of(), 1)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(2, proposal.graph().getNodes().size());
        assertTrue(provider.requests.get(2).userPrompt().contains("Call plan_graph"));
    }

    @Test
    void failedValidationReturnsRepairOrientedRelationships() {
        JsonArray invalid = new JsonArray();
        invalid.add(addNode("start", "START"));
        invalid.add(addNode("repeat", "CONTROL_REPEAT"));
        invalid.add(connect("start", "repeat", 0, 0));
        JsonArray repair = new JsonArray();
        repair.add(addNode("jump", "JUMP"));
        JsonObject attach = emptyCommand("attach_action");
        attach.addProperty("host", "repeat");
        attach.addProperty("child", "jump");
        repair.add(attach);
        FakeProvider provider = new FakeProvider(
            planAction("new", "Repeat a jump", "START", "CONTROL_REPEAT", "JUMP"),
            action("apply_graph_commands", "new", invalid, List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("apply_graph_commands", "new", repair, List.of(), 1),
            action("validate_graph", "new", null, List.of(), 2),
            action("finish", "new", null, List.of(), 2)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Jump repeatedly", "",
            simpleGraph(), "Open", true, true).join();

        String repairContext = provider.requests.get(3).userPrompt();
        assertTrue(repairContext.contains("missing_action_attachment"));
        assertTrue(repairContext.contains("suggestedOperations"));
        assertTrue(repairContext.contains("attach_action"));
        assertEquals(3, proposal.graph().getNodes().size());
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
        JsonArray start = new JsonArray();
        start.add(addNode("start", "START"));
        JsonArray jump = new JsonArray();
        jump.add(addNode("jump", "JUMP"));
        JsonArray connection = new JsonArray();
        connection.add(connect("start", "jump", 0, 0));
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of(), 0),
            planAction("new", "Build a valid jump sequence", "START", "JUMP"),
            action("apply_graph_commands", null, start, List.of(), 0),
            action("apply_graph_commands", null, jump, List.of(), 0),
            action("apply_graph_commands", null, jump, List.of(), 1),
            action("apply_graph_commands", null, connection, List.of(), 2),
            action("validate_graph", null, null, List.of(), 3),
            action("preview_execution", null, null, List.of(), 3),
            action("finish", null, null, List.of(), 3)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(2, proposal.graph().getNodes().size());
        assertTrue(provider.requests.get(4).userPrompt().contains("Draft revision mismatch"));
        assertTrue(provider.requests.get(4).userPrompt().contains("\"draftRevision\":1"));
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
    void exampleLookupUsesStructuralTraitsWithoutReturningUnrelatedGraphs() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            exampleAction("REPEAT_UNTIL"),
            action("inspect_preset", null, null, List.of()),
            action("finish", null, null, List.of())
        );

        AiPresetAgent.run(provider, "model", "Show the repeat-until pattern", "",
            simpleGraph(), "Open", true, true).join();

        String transcript = provider.requests.get(2).userPrompt();
        assertTrue(transcript.contains("repeat-until"));
        assertFalse(transcript.contains("onboarding-1"));
        assertFalse(transcript.contains("\"graph\""));
    }

    @Test
    void compactQueriesFindAndInspectExistingNodes() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "inspect", null, List.of()),
            queryAction("find_nodes", "JUMP", null, 1),
            queryAction("inspect_subgraph", null, "jump", 1),
            action("finish", null, null, List.of())
        );

        AiPresetAgent.run(provider, "model", "Inspect the jump", "", simpleGraph(), "Open", true, true).join();

        assertTrue(provider.requests.get(2).userPrompt().contains("Found 1 matching node"));
        assertTrue(provider.requests.get(3).userPrompt().contains("compact 2-node neighborhood"));
        assertFalse(provider.requests.get(3).userPrompt().contains("customNodeDefinition"));
    }

    @Test
    void simpleCreationCanCompleteInFiveProviderCallsWithPlanning() {
        FakeProvider provider = new FakeProvider(
            describeActionForTarget("new", "START", "JUMP"),
            planAction("new", "Build a valid jump sequence", "START", "JUMP"),
            action("apply_graph_commands", "new", simpleGraphCommands(), List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("finish", "new", null, List.of("Validated and previewed."), 1)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a jump preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(5, provider.requests.size());
        assertTrue(proposal.review() != null && !proposal.review().executionPaths().isEmpty());
    }

    @Test
    void distinctRecoverableErrorsDoNotExhaustTheStagnationGuard() {
        FakeProvider provider = new FakeProvider(
            action("select_target", "new", null, List.of(), 0),
            action("not_a_tool", "new", null, List.of(), 0),
            planAction("new", "Build a valid complex graph", "START", "JUMP"),
            action("apply_graph_commands", "new", simpleGraphCommands(), List.of(), 1),
            action("validate_graph", "new", null, List.of(), 0),
            action("apply_graph_commands", "new", simpleGraphCommands(), List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("finish", "new", null, List.of(), 1)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Make a complex preset", "",
            simpleGraph(), "Open", true, true).join();

        assertEquals(8, provider.requests.size());
        assertEquals(2, proposal.graph().getNodes().size());
    }

    @Test
    void semanticRequirementsRejectAValidButWrongCraftAmountUntilRepaired() {
        JsonObject plan = JsonParser.parseString(
            planAction("new", "Craft four oak planks", "START", "CRAFT")).getAsJsonObject();
        plan.getAsJsonArray("planRequirements").add(requirement("craft", "CRAFT", "Item", "minecraft:oak_planks"));
        plan.getAsJsonArray("planRequirements").add(requirement("craft", "CRAFT", "Amount", "4"));

        JsonArray wrong = new JsonArray();
        JsonObject sequence = emptyCommand("add_sequence");
        JsonArray refs = new JsonArray(); refs.add("start"); refs.add("craft");
        JsonArray types = new JsonArray(); types.add("START"); types.add("CRAFT");
        sequence.add("refs", refs); sequence.add("nodeTypes", types); wrong.add(sequence);
        wrong.add(addNode("input", "PARAM_ITEM"));
        JsonObject inputValue = emptyCommand("set_parameter");
        inputValue.addProperty("ref", "input");
        inputValue.addProperty("parameterId", "Item");
        inputValue.addProperty("value", "minecraft:oak_planks");
        wrong.add(inputValue);
        JsonObject attachment = emptyCommand("attach_parameter");
        attachment.addProperty("host", "craft"); attachment.addProperty("child", "input");
        attachment.addProperty("slotIndex", 0); wrong.add(attachment);

        JsonArray repair = new JsonArray();
        JsonObject amount = emptyCommand("set_parameter");
        amount.addProperty("ref", "craft");
        amount.addProperty("parameterId", "amount");
        amount.addProperty("value", "4");
        repair.add(amount);
        JsonArray overriddenRepair = new JsonArray();
        overriddenRepair.add(setParameters("craft", "minecraft:oak_planks", "4"));
        FakeProvider provider = new FakeProvider(
            plan.toString(),
            action("apply_graph_commands", "new", wrong, List.of(), 0),
            action("validate_graph", "new", null, List.of(), 1),
            action("apply_graph_commands", "new", overriddenRepair, List.of(), 1),
            action("apply_graph_commands", "new", repair, List.of(), 1),
            action("validate_graph", "new", null, List.of(), 2),
            action("finish", "new", null, List.of(), 2)
        );

        AiPresetService.Proposal proposal = AiPresetAgent.run(provider, "model", "Craft 4 oak planks", "",
            simpleGraph(), "Open", true, true).join();

        assertTrue(provider.requests.get(3).userPrompt().contains("requirement_mismatch"));
        assertTrue(provider.requests.get(3).userPrompt().contains("draft contains '1'"));
        assertTrue(provider.requests.get(4).userPrompt().contains("parameter_overridden"));
        assertTrue(provider.requests.get(4).userPrompt().contains("instanceContext"));
        assertTrue(provider.requests.get(4).userPrompt().contains("sourceParameters"));
        assertTrue(proposal.review().changes().stream().anyMatch(line -> line.contains("Amount=4")));
    }

    private static String action(String tool, String target, JsonArray commands, List<String> workLog) {
        return action(tool, target, commands, workLog, 0);
    }

    private static String action(String tool, String target, JsonArray commands, List<String> workLog, int draftRevision) {
        JsonObject action = new JsonObject();
        action.addProperty("tool", tool);
        if (target == null) action.add("target", null); else action.addProperty("target", target);
        action.add("nodeTypes", new JsonArray());
        action.add("exampleTraits", new JsonArray());
        action.add("planGoal", null);
        action.add("planSteps", new JsonArray());
        action.add("planNodeTypes", new JsonArray());
        action.add("planStructures", new JsonArray());
        action.add("planAssumptions", new JsonArray());
        action.add("planRequirements", new JsonArray());
        action.add("nodeRefs", new JsonArray());
        action.add("query", null);
        action.add("radius", null);
        action.add("exampleId", null);
        action.addProperty("draftRevision", draftRevision);
        action.add("commands", commands == null ? new JsonArray() : commands);
        action.addProperty("title", "Jump preset");
        action.addProperty("response", "Prepared for review.");
        JsonArray log = new JsonArray();
        workLog.forEach(log::add);
        action.add("workLog", log);
        return action.toString();
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
        action.add("exampleTraits", new JsonArray());
        action.add("planGoal", null);
        action.add("planSteps", new JsonArray());
        action.add("planNodeTypes", new JsonArray());
        action.add("planStructures", new JsonArray());
        action.add("planAssumptions", new JsonArray());
        action.add("planRequirements", new JsonArray());
        action.add("nodeRefs", new JsonArray());
        action.add("query", null);
        action.add("radius", null);
        action.add("exampleId", null);
        action.addProperty("draftRevision", 0);
        action.add("commands", new JsonArray());
        action.add("title", null);
        action.add("response", null);
        action.add("workLog", new JsonArray());
        return action.toString();
    }

    private static String queryAction(String tool, String type, String ref, int radius) {
        JsonObject action = JsonParser.parseString(action(tool, null, null, List.of())).getAsJsonObject();
        if (type != null) action.getAsJsonArray("nodeTypes").add(type);
        if (ref != null) action.getAsJsonArray("nodeRefs").add(ref);
        action.addProperty("radius", radius);
        return action.toString();
    }

    private static String exampleAction(String... traits) {
        JsonObject action = JsonParser.parseString(action("list_examples", null, null, List.of())).getAsJsonObject();
        for (String trait : traits) action.getAsJsonArray("exampleTraits").add(trait);
        return action.toString();
    }

    private static String planAction(String target, String goal, String... types) {
        JsonObject action = JsonParser.parseString(action("plan_graph", target, null, List.of())).getAsJsonObject();
        action.addProperty("planGoal", goal);
        action.getAsJsonArray("planSteps").add("Create the requested control-flow structure.");
        action.getAsJsonArray("planSteps").add("Validate and repair exact structural issues.");
        for (String type : types) action.getAsJsonArray("planNodeTypes").add(type);
        action.getAsJsonArray("planStructures").add("sequence");
        return action.toString();
    }

    private static JsonArray simpleGraphCommands() {
        JsonArray commands = new JsonArray();
        commands.add(addNode("start", "START"));
        commands.add(addNode("jump", "JUMP"));
        commands.add(connect("start", "jump", 0, 0));
        return commands;
    }

    private static JsonObject requirement(String ref, String nodeType, String parameterId, String value) {
        JsonObject requirement = new JsonObject();
        requirement.addProperty("ref", ref);
        requirement.addProperty("nodeType", nodeType);
        requirement.addProperty("parameterId", parameterId);
        requirement.addProperty("value", value);
        return requirement;
    }

    private static JsonObject setParameters(String ref, String item, String amount) {
        JsonObject command = emptyCommand("set_parameters");
        command.addProperty("ref", ref);
        JsonArray values = new JsonArray();
        JsonObject itemValue = new JsonObject();
        itemValue.addProperty("parameterId", "Item");
        itemValue.addProperty("value", item);
        values.add(itemValue);
        JsonObject amountValue = new JsonObject();
        amountValue.addProperty("parameterId", "Amount");
        amountValue.addProperty("value", amount);
        values.add(amountValue);
        command.add("parameterValues", values);
        return command;
    }

    private static JsonObject addNode(String ref, String type) {
        JsonObject command = emptyCommand("add_node");
        command.addProperty("ref", ref);
        command.addProperty("nodeType", type);
        return command;
    }

    private static JsonObject connect(String from, String to, int outputSocket, int inputSocket) {
        JsonObject command = emptyCommand("connect");
        command.addProperty("from", from);
        command.addProperty("to", to);
        command.addProperty("outputSocket", outputSocket);
        command.addProperty("inputSocket", inputSocket);
        return command;
    }

    private static JsonObject emptyCommand(String kind) {
        JsonObject command = new JsonObject();
        command.addProperty("kind", kind);
        for (String field : List.of("ref", "nodeType", "mode", "parameterId", "value", "from", "to",
            "outputSocket", "inputSocket", "host", "child", "slotIndex", "count", "sensor", "variableRef",
            "name", "routineRef")) command.add(field, null);
        for (String field : List.of("refs", "newRefs", "replacementRefs", "nodeTypes", "trueRefs", "falseRefs",
            "routineInputs")) command.add(field, new JsonArray());
        return command;
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
            // Legacy fixtures explicitly supplied graph targets; supply the separate request assessment.
            if (requests.size() == 1) {
                JsonObject first = JsonParser.parseString(response).getAsJsonObject();
                String target = first.has("target") && !first.get("target").isJsonNull() ? first.get("target").getAsString() : "inspect";
                first.addProperty("requestIntent", target.equals("new") ? "build" : target.equals("current") ? "edit" : "diagnose");
                String user = request.userPrompt().split("USER_REQUEST:\n", 2)[1].split("\nREQUEST_SCOPE:", 2)[0];
                first.addProperty("intentEvidence", user);
                response = first.toString();
            }
            return CompletableFuture.completedFuture(response);
        }
    }
}

package com.pathmind.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphPersistence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiLifecycleWorkflowTest {
    private static AiBehaviorEvalCase workflow() {
        return AiBehaviorEvalCase.load().stream().filter(c -> c.id().equals("lifecycle-position-return")).findFirst().orElseThrow();
    }
    @Test void exactWorkflowRecoversFromInspectScopeAndPreservesOriginalGraphWithConciseAnswer() {
        var test = workflow(); var original = test.activeGraph();
        String before = new Gson().toJson(original);
        var inspect = action("inspect_preset", "inspect");
        var assess = action("assess_request", "undecided"); assess.addProperty("requestIntent", "edit"); assess.addProperty("intentEvidence", test.prompt());
        var prematureFinish = action("finish", "inspect");
        var plan = action("plan_graph", "current"); plan.addProperty("planGoal", "Preserve the original chain, capture position, walk five blocks, return with pathfinding");
        plan.add("planSteps", JsonParser.parseString("[\"Capture Self position after Jump\",\"Walk forward five blocks\",\"Go to the same saved variable\"]"));
        var patch = action("apply_graph_commands", "current"); patch.addProperty("draftRevision", 0); patch.add("commands", AiReportedWorkflowFixture.extension());
        var finish = action("finish", "current");
        String response = "I preserved the existing chain and proposed saving your position after Jump, walking five blocks, then returning there with pathfinding. Review the graph before applying it.";
        finish.addProperty("response", response);
        var actions = new ArrayDeque<JsonObject>(List.of(inspect, assess, prematureFinish, plan, patch, action("validate_graph", "current"), finish));
        List<AiPresetRequest> requests = new ArrayList<>();
        AiProvider provider = request -> { requests.add(request); return CompletableFuture.completedFuture(actions.removeFirst().toString()); };
        var progress = new ArrayList<AiRequestProgress>();
        var context = AiWorkspaceContext.attach("{}", "Eval fixture", original, List.of("reported-jump"));
        var run = AiPresetAgent.runMeasured(provider, "scripted-test", test.prompt(), context, original, "Eval fixture", true, true, new AiRequestControl(progress::add)).join();
        assertTrue(run.succeeded(), run.error());
        var grade = AiBehaviorEvalGrader.gradeRun(test, run);
        assertTrue(grade.passed(), grade.failures().toString());
        assertEquals(before, new Gson().toJson(original), "Draft edits must not mutate the real fixture");
        assertEquals(response.strip(), run.proposal().response());
        assertTrue(run.proposal().response().length() <= 600);
        assertTrue(run.trace().stream().anyMatch(t -> !t.success() && t.code().equals("proposal_required")));
        for (var stage : List.of(AiRequestProgress.Stage.INSPECTING, AiRequestProgress.Stage.PLANNING, AiRequestProgress.Stage.BUILDING, AiRequestProgress.Stage.VALIDATING, AiRequestProgress.Stage.AWAITING_REVIEW))
            assertTrue(progress.stream().anyMatch(p -> p.stage() == stage), stage.toString());
        assertTrue(requests.getFirst().userPrompt().contains("reported-jump"));
    }

    @Test void validFiveSecondsGraphFailsFiveBlocksBehaviorGrade() {
        var commands = JsonParser.parseString(AiReportedWorkflowFixture.extension().toString()
            .replace("PARAM_DISTANCE", "PARAM_DURATION").replace("\"parameterId\":\"distance\"", "\"parameterId\":\"duration\"")).getAsJsonArray();
        var result = AiGraphCommandEngine.apply(new Gson().toJsonTree(AiReportedWorkflowFixture.original()).getAsJsonObject(), commands, Map.of(), true, true);
        assertTrue(result.success(), result.message());
        var graph = NodeGraphPersistence.parseNodeGraphData(result.graph().toString());
        var grade = AiBehaviorEvalGrader.grade(workflow(), new AiPresetService.Proposal("Return", "", List.of(), graph, "current"));
        assertTrue(grade.validationPassed(), grade.failures().toString());
        assertFalse(grade.behaviorPresent());
        assertTrue(grade.failures().stream().anyMatch(f -> f.contains("PARAM_DISTANCE")));
    }

    @Test void differentReturnVariableAndCaptureInWrongOrderFailEvenWhenGraphValidates() {
        var commands = AiReportedWorkflowFixture.extension();
        commands.add(JsonParser.parseString("{\"kind\":\"set_parameter\",\"ref\":\"homeReader\",\"parameterId\":\"variable\",\"value\":\"elsewhere\"}"));
        var result = AiGraphCommandEngine.apply(new Gson().toJsonTree(AiReportedWorkflowFixture.original()).getAsJsonObject(), commands, Map.of(), true, true);
        assertTrue(result.success(), result.message());
        var wrongVariable = AiBehaviorEvalGrader.grade(workflow(), new AiPresetService.Proposal("Return", "", List.of(), NodeGraphPersistence.parseNodeGraphData(result.graph().toString()), "current"));
        assertTrue(wrongVariable.validationPassed(), wrongVariable.failures().toString());
        assertFalse(wrongVariable.behaviorPresent());
        assertTrue(wrongVariable.failures().stream().anyMatch(f -> f.contains("shared variable")));
        var graph = AiReportedWorkflowFixture.completed();
        var save = graph.getNodes().stream().filter(n -> n.getType() == com.pathmind.nodes.NodeType.SET_VARIABLE).findFirst().orElseThrow();
        var walk = graph.getNodes().stream().filter(n -> n.getType() == com.pathmind.nodes.NodeType.WALK && !n.getId().equals("reported-initialWalk")).findFirst().orElseThrow();
        // Swap save/walk in the flow without changing any sockets or parameter relationships.
        for (var edge : graph.getConnections()) {
            if (edge.getOutputNodeId().equals("reported-jump")) edge.setInputNodeId(walk.getId());
            else if (edge.getOutputNodeId().equals(save.getId())) { edge.setOutputNodeId(walk.getId()); edge.setInputNodeId(save.getId()); }
            else if (edge.getOutputNodeId().equals(walk.getId())) edge.setOutputNodeId(save.getId());
        }
        var wrongOrder = AiBehaviorEvalGrader.grade(workflow(), new AiPresetService.Proposal("Return", "", List.of(), graph, "current"));
        assertTrue(wrongOrder.validationPassed(), wrongOrder.failures().toString());
        assertFalse(wrongOrder.behaviorPresent());
        assertTrue(wrongOrder.failures().stream().anyMatch(f -> f.contains("ordered flow")));
    }
    private static JsonObject action(String tool, String target) {
        var action = new JsonObject(); action.addProperty("tool", tool); action.addProperty("target", target); action.addProperty("response", "Result"); return action;
    }
}

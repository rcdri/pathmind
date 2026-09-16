package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestIntentTest {
    @Test void replyToClarificationCannotBeClassifiedAsAnotherClarification() {
        String user = "you choose";
        String context = """
            {"conversation":{"messages":[
              {"role":"user","text":"Build a new preset that walks then repeats Jump twice."},
              {"role":"event","text":"Request outcome: CLARIFICATION. No preset changes applied."},
              {"role":"assistant","text":"How long should it walk?"}]}}
            """;
        assertTrue(AiPresetAgent.pendingClarification(context));
        var askAgain = decision("finish", "undecided", "clarify", user);
        askAgain.addProperty("completion", "clarification"); askAgain.addProperty("completionReason", "Choice missing");
        askAgain.addProperty("response", "Which duration should I use?");
        var answer = decision("finish", "inspect", "discuss", user); answer.addProperty("response", "I'll choose a safe default.");
        var report = AiPresetAgent.runMeasured(new Script(askAgain, answer), "test-model", user, context,
            fixture(), "Open", true, true).join();
        assertTrue(report.succeeded(), report.error());
        assertEquals(AiCompletionOutcome.ANSWER, report.proposal().outcome());
        assertTrue(report.trace().stream().anyMatch(step -> step.message().contains("answers the previous clarification")));
    }

    @Test void clarificationCannotDiscardAnEditedDraft() {
        String user = "Build a new preset and choose the defaults";
        var plan = action("plan_graph", "new"); plan.addProperty("planGoal", "Create a starting preset");
        plan.add("planSteps", JsonParser.parseString("[\"Create Start\"]"));
        var patch = action("apply_graph_commands", "new"); patch.addProperty("draftRevision", 0);
        patch.add("commands", JsonParser.parseString("[{\"kind\":\"add_node\",\"ref\":\"start\",\"nodeType\":\"START\"}]"));
        var escape = action("finish", "new"); escape.addProperty("completion", "clarification");
        escape.addProperty("completionReason", "Need a name"); escape.addProperty("response", "What should it be named?");
        var report = run(new Script(decision("select_target", "new", "build", user), plan, patch, escape,
            action("validate_graph", "new"), action("finish", "new")), user, null);
        assertTrue(report.succeeded(), report.error());
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("clarification_after_edit")));
    }

    @Test void delegatedClarificationReplyCanContinueIntoAValidatedBuild() {
        String user = "you choose";
        String context = """
            {"conversation":{"messages":[
              {"role":"user","text":"Make a new preset that walks forward, then jumps twice using Repeat."},
              {"role":"event","text":"Request outcome: CLARIFICATION. No preset changes applied."},
              {"role":"assistant","text":"How long should it walk?"}]}}
            """;
        var plan = action("plan_graph", "new"); plan.addProperty("planGoal", "Walk forward briefly, then repeat Jump twice");
        plan.add("planSteps", JsonParser.parseString("[\"Walk forward using typed inputs\",\"Repeat Jump twice\"]"));
        var patch = action("apply_graph_commands", "new"); patch.addProperty("draftRevision", 0);
        patch.add("commands", JsonParser.parseString("""
            [{"kind":"add_sequence","refs":["start","walk","repeat"],"nodeTypes":["START","WALK","CONTROL_REPEAT"]},
             {"kind":"add_node","ref":"look","nodeType":"SENSOR_LOOK_DIRECTION"},
             {"kind":"add_node","ref":"duration","nodeType":"PARAM_DURATION"},
             {"kind":"set_parameter","ref":"duration","parameterId":"duration","value":"1"},
             {"kind":"attach_parameter","host":"walk","child":"look","slotIndex":0},
             {"kind":"attach_parameter","host":"walk","child":"duration","slotIndex":1},
             {"kind":"set_parameter","ref":"repeat","parameterId":"count","value":"2"},
             {"kind":"add_node","ref":"jump","nodeType":"JUMP"},
             {"kind":"attach_action","host":"repeat","child":"jump"}]
            """));
        var report = AiPresetAgent.runMeasured(new Script(decision("select_target", "new", "build", user), plan, patch,
            action("validate_graph", "new"), action("finish", "new")), "test-model", user, context,
            fixture(), "Open", true, true).join();
        assertTrue(report.succeeded(), report.error());
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
        assertEquals("2", report.proposal().graph().getNodes().stream()
            .filter(node -> node.getType() == NodeType.CONTROL_REPEAT).findFirst().orElseThrow()
            .getParameters().stream().filter(parameter -> "count".equals(parameter.getId())).findFirst().orElseThrow()
            .getValue());
    }

    @Test void clarificationCannotBeUsedToReturnAnEditDescriptionInsteadOfAQuestion() {
        String user = "Extend this preset";
        var advice = decision("finish", "undecided", "edit", user);
        advice.addProperty("completion", "clarification");
        advice.addProperty("completionReason", "Behavior missing"); advice.addProperty("response", "I should add another action.");
        var question = advice.deepCopy(); question.addProperty("response", "What should happen after the current sequence?");
        var report = run(new Script(advice, question), user, fixture());
        assertTrue(report.succeeded(), report.error());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("clarification_question_required")));
    }
    private static final String EDIT = "make the preset: after it jumps, create a variable for the players current position, walk forward 5 blocks, then travel back to that position with pathfinding";

    @Test void oversizedOrdinaryReplyIsRejectedAndCorrectedReplyCanFinish() {
        String user = "What does this preset do?";
        var tooLong = decision("finish", "undecided", "discuss", user);
        tooLong.addProperty("response", "explanation ".repeat(100));
        var concise = action("finish", "undecided");
        concise.addProperty("response", "It runs the current sequence in order.");
        var report = run(new Script(tooLong, concise), user, fixture());
        assertTrue(report.succeeded(), report.error());
        assertEquals("It runs the current sequence in order.", report.proposal().response());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("response_too_long")));
    }

    @Test void explicitlyRequestedDetailedReplyGetsALargerBudget() {
        String user = "Give a complete explanation of at least 500 characters.";
        var detailed = decision("finish", "undecided", "discuss", user);
        detailed.addProperty("response", "useful detail ".repeat(60));
        var report = run(new Script(detailed), user, fixture());
        assertTrue(report.succeeded(), report.error());
        assertTrue(report.proposal().response().length() >= 500);
    }

    @Test void repeatedInspectionGetsRecoveryGuidanceAndCanStillProduceAProposal() {
        String user = "Append a wait to this preset";
        var report = run(new Script(decision("inspect_preset", "current", "edit", user),
            action("inspect_preset", "current"), action("inspect_preset", "current"),
            action("inspect_preset", "current"), plan("current"), patch("current"),
            action("validate_graph", "current"), action("finish", "current")), user, fixture());
        assertTrue(report.succeeded(), report.error());
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("recover_repeated_action")));
        assertEquals(2, fixture().getNodes().size());
    }

    @Test void correctedFinishIsNotMistakenForTheSameAction() {
        String user = "Extend this preset";
        var question = action("finish", "undecided");
        question.addProperty("completion", "clarification");
        question.addProperty("completionReason", "No requested behavior is specified, so there is no safe extension to choose.");
        question.addProperty("response", "What should happen next?");
        var report = run(new Script(decision("assess_request", "undecided", "edit", user),
            action("finish", "undecided"), action("finish", "undecided"), question), user, fixture());
        assertTrue(report.succeeded(), report.error());
        assertEquals(AiCompletionOutcome.CLARIFICATION, report.proposal().outcome());
        assertFalse(report.trace().stream().anyMatch(step -> step.code().equals("recover_repeated_action")));
    }

    @Test void ignoringRecoveryGuidanceStillStopsWithinABoundedBudget() {
        String user = "Inspect this preset";
        var report = run(new Script(decision("inspect_preset", "inspect", "diagnose", user),
            action("inspect_preset", "inspect"), action("inspect_preset", "inspect"),
            action("inspect_preset", "inspect"), action("inspect_preset", "inspect"),
            action("inspect_preset", "inspect")), user, fixture());
        assertFalse(report.succeeded());
        assertTrue(report.error().contains("could not recover"));
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("recover_repeated_action")));
        assertNull(report.proposal());
    }

    @Test void inspectingBeforeAssessmentDoesNotTrapExplicitEditInReadOnlyMode() {
        var first = action("inspect_preset", "inspect");
        var assessment = decision("assess_request", "undecided", "edit", EDIT);
        var prematureFinish = action("finish", "inspect");
        var plan = plan("current");
        var apply = patch("current");
        var report = run(new Script(first, assessment, prematureFinish, plan, apply,
            action("validate_graph", "current"), action("finish", "current")), EDIT, fixture());
        assertTrue(report.succeeded());
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
        assertTrue(report.proposal().editsCurrentPreset());
        assertEquals(3, report.proposal().graph().getNodes().size());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("proposal_required") && !step.success()));
        var mutation = report.trace().stream().filter(step -> step.tool().equals("apply_graph_commands")).findFirst().orElseThrow();
        assertEquals(0, mutation.revisionBefore()); assertEquals(1, mutation.revisionAfter());
        // This regression tests permissions/routing with a minimal patch, not the full world-dependent behavior.
        assertEquals(2, fixture().getNodes().size());
    }

    @Test void discussionAnswersWithoutInspectingAndCannotEscalateToEdits() {
        String user = "What do you think about this design?";
        var first = decision("finish", "undecided", "discuss", user);
        var report = run(new Script(first), user, null);
        assertTrue(report.succeeded());
        assertEquals(AiCompletionOutcome.ANSWER, report.proposal().outcome());
        assertFalse(report.proposal().changesGraph());
        assertEquals(1, report.trace().size());
        var escalation = decision("plan_graph", "current", "edit", user);
        escalation.addProperty("planGoal", "Edit anyway");
        var guarded = run(new Script(decision("assess_request", "undecided", "discuss", user), escalation,
            action("finish", "undecided")), user, fixture());
        assertTrue(guarded.succeeded());
        assertTrue(guarded.trace().stream().anyMatch(step -> !step.success()));
        assertFalse(guarded.proposal().changesGraph());
    }

    @Test void unassessedRequestCannotFinishAndHistoricalQuoteCannotAuthorizeEditing() {
        var first = action("inspect_preset", "inspect");
        var bad = decision("assess_request", "current", "edit", "an older request");
        var script = new Script(first, bad, action("finish", "inspect"));
        var report = run(script, "What do you think?", fixture());
        assertFalse(report.succeeded());
        assertTrue(report.trace().stream().filter(step -> step.code().equals("intent_required")).count() >= 2);
        assertNull(report.proposal());
    }

    @Test void clarificationAndBlockerAreDistinctNonMutatingOutcomes() {
        String user = "Extend this preset";
        var clarification = decision("finish", "undecided", "edit", user);
        clarification.addProperty("completion", "clarification");
        clarification.addProperty("completionReason", "The requested new behavior is unspecified.");
        clarification.addProperty("response", "What should happen after the current sequence?");
        var report = run(new Script(clarification), user, fixture());
        assertEquals(AiCompletionOutcome.CLARIFICATION, report.proposal().outcome());
        assertFalse(report.proposal().changesGraph());
        var blocker = action("finish", "undecided");
        blocker.addProperty("completion", "blocked");
        blocker.addProperty("completionReason", "There is no open preset to edit.");
        blocker.addProperty("response", "Open the preset you want me to extend.");
        blocker.addProperty("blockingToolTurn", 2);
        assertEquals(AiCompletionOutcome.BLOCKED, run(new Script(decision("assess_request", "undecided", "edit", user),
            action("select_target", "current"), blocker), user, null).proposal().outcome());
    }

    @Test void missingBlockerReasonIsRejectedAndScopeCannotSwitchAfterMutation() {
        String user = "Change this preset";
        var first = decision("assess_request", "undecided", "edit", user);
        var invalidFinish = action("finish", "undecided"); invalidFinish.addProperty("completion", "blocked");
        var report = run(new Script(first, invalidFinish, action("inspect_preset", "inspect"), plan("current"), patch("current"),
            action("select_target", "new"), action("validate_graph", "current"), action("finish", "current")), user, fixture());
        assertTrue(report.succeeded());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("missing_completion_reason")));
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("scope_conflict")));
        assertTrue(report.proposal().editsCurrentPreset());
    }

    @Test void workLogsComeOnlyFromActualToolsAndIntentDoesNotCarryBetweenRequests() {
        String user = "What is wrong with this preset?";
        var finish = action("finish", "inspect");
        JsonArray claims = new JsonArray(); claims.add("I edited everything and applied it."); finish.add("workLog", claims);
        var report = run(new Script(decision("inspect_preset", "inspect", "diagnose", user), finish), user, fixture());
        assertTrue(report.succeeded());
        assertFalse(report.proposal().workLog().toString().contains("edited everything"));
        assertTrue(report.proposal().workLog().toString().contains("Returned the open preset"));
        var fresh = run(new Script(action("finish", "inspect")), user, fixture());
        assertFalse(fresh.succeeded());
        assertTrue(fresh.trace().stream().anyMatch(step -> step.code().equals("intent_required")));
        assertFalse(AiRequestDiagnostics.recent().isEmpty());
    }

    @Test void aPermissionErrorCannotBeUsedAsEvidenceThatEditingIsImpossible() {
        String user = "Change this preset";
        var badBlocker = action("finish", "undecided");
        badBlocker.addProperty("completion", "blocked");
        badBlocker.addProperty("completionReason", "Inspect mode prevents editing.");
        badBlocker.addProperty("blockingToolTurn", 2);
        var report = run(new Script(decision("assess_request", "undecided", "edit", user), action("finish", "inspect"),
            badBlocker, action("inspect_preset", "inspect"), plan("current"), patch("current"), action("validate_graph", "current"), action("finish", "current")), user, fixture());
        assertTrue(report.succeeded());
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("unconfirmed_blocker")));
    }

    @Test void aRecoverableWiringConflictCannotBeUsedAsEvidenceThatEditingIsImpossible() {
        String user = "Append a wait to this preset";
        var blocked = action("finish", "current");
        blocked.addProperty("completion", "blocked");
        blocked.addProperty("completionReason", "The output was already connected.");
        blocked.addProperty("response", "I could not update the preset.");
        blocked.addProperty("blockingToolTurn", 4);
        var rejectedPatch = action("apply_graph_commands", "current");
        rejectedPatch.addProperty("draftRevision", 0);
        rejectedPatch.add("commands", JsonParser.parseString("""
            [{"kind":"add_node","ref":"wait","nodeType":"WAIT"},
             {"kind":"connect","from":"start","to":"wait","outputSocket":0,"inputSocket":0}]
            """));

        var report = run(new Script(decision("assess_request", "current", "edit", user),
            action("inspect_preset", "current"), plan("current"), rejectedPatch, blocked,
            patch("current"), action("validate_graph", "current"), action("finish", "current")), user, fixture());

        assertTrue(report.succeeded(), report.error());
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("occupied_output")));
        assertTrue(report.trace().stream().anyMatch(step -> step.code().equals("unconfirmed_blocker")));
        assertEquals(AiCompletionOutcome.PROPOSAL, report.proposal().outcome());
    }

    @Test void nativeOpenAiToolsCarryIntentThroughActualCallResultPairing() {
        String user = "What do you think about routines?";
        var responses = new ArrayDeque<JsonObject>();
        var assess = decision("assess_request", "undecided", "discuss", user);
        responses.add(nativeResponse("c1", assess));
        responses.add(nativeResponse("c2", action("finish", "undecided")));
        List<JsonObject> bodies = new ArrayList<>();
        var provider = new OpenAiResponsesProvider("https://example.invalid", "test-key", false, (endpoint, headers, body) -> {
            bodies.add(body.deepCopy()); return CompletableFuture.completedFuture(responses.removeFirst());
        });
        var report = AiPresetAgent.runMeasured(provider, "test-model", user, "", null, "", true, true).join();
        assertTrue(report.succeeded());
        assertEquals(AiCompletionOutcome.ANSWER, report.proposal().outcome());
        assertEquals(2, bodies.size());
        var outputs = bodies.get(1).getAsJsonArray("input");
        var result = outputs.get(2).getAsJsonObject();
        assertEquals("c1", result.get("call_id").getAsString());
        assertTrue(result.get("output").getAsString().contains("discuss"));
        assertFalse(result.get("output").getAsString().contains("draftEditsAuthorized\":true"));
    }

    private static JsonObject nativeResponse(String callId, JsonObject action) {
        var args = action.deepCopy(); String name = args.remove("tool").getAsString();
        var call = new JsonObject(); call.addProperty("type", "function_call"); call.addProperty("call_id", callId);
        call.addProperty("name", name); call.addProperty("arguments", args.toString());
        JsonArray output = new JsonArray(); output.add(call);
        JsonObject response = new JsonObject(); response.addProperty("status", "completed"); response.add("output", output); return response;
    }

    private static AiRunReport run(Script provider, String user, NodeGraphData graph) {
        return AiPresetAgent.runMeasured(provider, "test-model", user, "Historical context is not authorization.", graph, "Open", true, true).join();
    }
    private static JsonObject action(String tool, String target) {
        JsonObject action = new JsonObject(); action.addProperty("tool", tool); action.addProperty("target", target);
        action.addProperty("title", "Result"); action.addProperty("response", "Result for review."); return action;
    }
    private static JsonObject decision(String tool, String target, String intent, String evidence) {
        var action = action(tool, target); action.addProperty("requestIntent", intent); action.addProperty("intentEvidence", evidence); return action;
    }
    private static JsonObject plan(String target) {
        var action = action("plan_graph", target); action.addProperty("planGoal", "Append a wait to the sequence");
        JsonArray steps = new JsonArray(); steps.add("Append and connect WAIT"); action.add("planSteps", steps); return action;
    }
    private static JsonObject patch(String target) {
        var action = action("apply_graph_commands", target); action.addProperty("draftRevision", 0);
        action.add("commands", JsonParser.parseString("""
            [{"kind":"add_node","ref":"wait","nodeType":"WAIT"},
             {"kind":"connect","from":"jump","to":"wait","outputSocket":0,"inputSocket":0}]
            """)); return action;
    }
    private static NodeGraphData fixture() {
        var start = new NodeGraphData.NodeData("start", NodeType.START, null, 0, 0, new ArrayList<>());
        var jump = new NodeGraphData.NodeData("jump", NodeType.JUMP, null, 200, 0, new ArrayList<>());
        return new NodeGraphData(new ArrayList<>(List.of(start, jump)), new ArrayList<>(List.of(new NodeGraphData.ConnectionData("start", "jump", 0, 0))));
    }
    private static final class Script implements AiProvider {
        final ArrayDeque<JsonObject> actions = new ArrayDeque<>();
        Script(JsonObject... actions) { this.actions.addAll(List.of(actions)); }
        public CompletableFuture<String> generate(AiPresetRequest request) {
            if (actions.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("End of test script."));
            return CompletableFuture.completedFuture(actions.removeFirst().toString());
        }
    }
}

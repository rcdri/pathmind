package com.pathmind.ai;

import com.google.gson.JsonParser;
import com.pathmind.nodes.NodeType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiBehaviorEvalTest {
    @Test void liveSelectionRejectsTyposAndSupportsExactWorkflowSamples() {
        var cases = AiBehaviorEvalCase.load();
        assertEquals(7, AiBehaviorEvalRunner.select(cases, "regression", "", 67).size());
        var selected = AiBehaviorEvalRunner.select(cases, "", "lifecycle-position-return", 1);
        assertEquals("lifecycle-position-return", selected.getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> AiBehaviorEvalRunner.select(cases, "", "typo", 1));
        assertThrows(IllegalArgumentException.class, () -> AiBehaviorEvalRunner.select(cases, "unrecognized", "", 1));
    }
    @Test void corpusHasSixtySevenUniqueRequestsAndValidFixtures() {
        var cases = AiBehaviorEvalCase.load();
        assertEquals(67, cases.size());
        assertEquals(67, cases.stream().map(AiBehaviorEvalCase::id).distinct().count());
        assertEquals(67, cases.stream().map(AiBehaviorEvalCase::prompt).distinct().count());
        assertEquals(7, cases.stream().filter(test -> test.difficulty().equals("regression")).count());
        for (String difficulty : List.of("simple", "medium", "complex"))
            assertEquals(20, cases.stream().filter(test -> test.difficulty().equals(difficulty)).count());
        for (var test : cases) {
            assertTrue(List.of("new", "current", "inspect").contains(test.target()));
            if (test.expected().has("requiredNodes")) test.expected().getAsJsonObject("requiredNodes").keySet().forEach(NodeType::valueOf);
            if (!test.fixture().isBlank()) assertNotNull(test.activeGraph());
        }
    }

    @Test void reachableJumpPassesButDisconnectedRequiredNodeFails() {
        var test = AiBehaviorEvalCase.load().stream().filter(item -> item.id().equals("simple-01")).findFirst().orElseThrow();
        var fixture = new AiBehaviorEvalCase("fixture", "", "simple", "new", "jump", test.expected());
        var graph = fixture.activeGraph();
        assertTrue(AiBehaviorEvalGrader.grade(test, new AiPresetService.Proposal("Jump", "", List.of(), graph, "new")).passed());
        graph.getConnections().clear();
        assertFalse(AiBehaviorEvalGrader.grade(test, new AiPresetService.Proposal("Jump", "", List.of(), graph, "new")).behaviorPresent());
    }

    @Test void costUsesCacheRatesAndNeverGuessesMissingUsage() {
        var rates = new AiRunMetrics.AiPricing(2, 1, 3, 4);
        var metrics = new AiRunMetrics("test", "model", 1, 0, 0, 0, 1,
            new AiTokenUsage(100L, 20L, 30L, 10L), java.util.Map.of());
        assertEquals(.00026, metrics.estimatedCost(rates), .00000001);
        var unknown = new AiRunMetrics("test", "model", 1, 0, 0, 0, 1, AiTokenUsage.UNKNOWN, java.util.Map.of());
        assertNull(unknown.estimatedCost(rates));
        assertNull(AiTokenUsage.total(List.of(metrics.usage(), AiTokenUsage.UNKNOWN)).inputTokens());
        assertThrows(IllegalArgumentException.class, () -> new AiRunMetrics.AiPricing(-1, 0, 0, 0));
    }

    @Test void validRepeatWithWrongCountFailsBehaviorAndExtraNodeFailsQuality() {
        var test = AiBehaviorEvalCase.load().stream().filter(item -> item.id().equals("medium-01")).findFirst().orElseThrow();
        var fixture = new AiBehaviorEvalCase("fixture", "", "medium", "new", "repeat-action", test.expected());
        var graph = fixture.activeGraph();
        var proposal = new AiPresetService.Proposal("Repeat", "", List.of(), graph, "new");
        assertTrue(AiBehaviorEvalGrader.grade(test, proposal).passed());
        graph.getNodes().stream().filter(node -> node.getType() == NodeType.CONTROL_REPEAT).findFirst().orElseThrow()
            .getParameters().get(0).setValue("10");
        var grade = AiBehaviorEvalGrader.grade(test, proposal);
        assertTrue(grade.validationPassed());
        assertFalse(grade.behaviorPresent());
    }

    @Test void ordinaryResponsesAreGradedForConcisenessButRequestedLongAnswersAreAllowed() {
        var inspect = AiBehaviorEvalCase.load().stream().filter(item -> item.id().equals("lifecycle-discuss-not-edit")).findFirst().orElseThrow();
        var longReply = new AiPresetService.Proposal("Answer", "x".repeat(700), List.of(), null, "inspect");
        assertFalse(AiBehaviorEvalGrader.grade(inspect, longReply).behaviorPresent());
        var detailed = AiBehaviorEvalCase.load().stream().filter(item -> item.id().equals("lifecycle-long-answer")).findFirst().orElseThrow();
        var requestedReply = new AiPresetService.Proposal("Answer", "seconds and blocks " + "x".repeat(700), List.of(), null, "inspect");
        assertTrue(AiBehaviorEvalGrader.grade(detailed, requestedReply).behaviorPresent());
    }
}

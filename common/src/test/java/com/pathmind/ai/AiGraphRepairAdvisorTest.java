package com.pathmind.ai;

import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeConnection;
import com.pathmind.nodes.NodeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGraphRepairAdvisorTest {
    @Test
    void turnsMissingActionTextIntoAnExecutableTargetedRepair() {
        Node start = new Node(NodeType.START, 0, 0);
        Node repeat = new Node(NodeType.CONTROL_REPEAT, 180, 0);
        NodeGraphData graph = NodeGraphPersistence.createGraphData(List.of(start, repeat),
            List.of(new NodeConnection(start, repeat, 0, 0)));
        AiPresetService.Validation validation = AiPresetService.validateProposal(
            new AiPresetService.Proposal("Draft", "", List.of(), graph, "new"), "", true, true);
        AiPresetService.ValidationIssue issue = validation.issues().stream()
            .filter(item -> item.message().contains("requires an action attachment")).findFirst().orElseThrow();

        JsonObject repair = AiGraphRepairAdvisor.describe(issue, graph, Map.of("repeat", repeat.getId()));

        assertEquals("missing_action_attachment", repair.get("code").getAsString());
        assertEquals(repeat.getId(), repair.get("nodeId").getAsString());
        assertTrue(repair.getAsJsonArray("expected").get(0).getAsString().contains("ACTION"));
        assertEquals("attach_action", repair.getAsJsonArray("suggestedOperations").get(0).getAsString());
        assertEquals("repeat", repair.getAsJsonArray("inspectRefs").get(0).getAsString());
    }

    @Test
    void includesExactMissingSlotContract() {
        Node look = new Node(NodeType.LOOK, 0, 0);
        NodeGraphData graph = NodeGraphPersistence.createGraphData(List.of(look), List.of());
        AiPresetService.ValidationIssue issue = new AiPresetService.ValidationIssue(
            "error", "missing_parameter_slot", look.getId(), null, "Missing parameter.");

        JsonObject repair = AiGraphRepairAdvisor.describe(issue, graph, Map.of());

        assertTrue(repair.getAsJsonArray("expected").get(0).getAsString().contains("slot 0"));
        assertEquals("attach_parameter", repair.getAsJsonArray("suggestedOperations").get(0).getAsString());
    }
}

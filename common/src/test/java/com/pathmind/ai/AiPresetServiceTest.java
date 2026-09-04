package com.pathmind.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.List;

class AiPresetServiceTest {
    @Test
    void inspectionResponsesDoNotRequireAGraph() {
        AiPresetService.Proposal proposal = AiPresetService.parseProposal("""
            {"target":"inspect","title":"Review","response":"Looks good.","workLog":[],"graph":null}
            """);

        assertFalse(proposal.changesGraph());
        assertNull(proposal.graph());
    }

    @Test
    void currentProposalOnlyMatchesTheExactPresetRevisionItStartedFrom() {
        NodeGraphData original = graph(false);
        String fingerprint = AiPresetService.graphFingerprint("Preset", original);
        AiPresetService.Proposal proposal = new AiPresetService.Proposal("Edit", "", List.of(), original,
            "current", fingerprint, AiProposalReview.create(original, original));

        assertTrue(AiPresetService.matchesSource(proposal, "Preset", graph(false)));
        assertFalse(AiPresetService.matchesSource(proposal, "Renamed", graph(false)));
        assertFalse(AiPresetService.matchesSource(proposal, "Preset", graph(true)));
    }

    @Test
    void validationKeepsMachineReadableWarningsWithoutBlockingTheDraft() {
        NodeGraphData disconnected = graph(true);
        AiPresetService.Validation validation = AiPresetService.validateProposal(
            new AiPresetService.Proposal("Draft", "", List.of(), disconnected, "new"), "", true, true);

        assertTrue(validation.valid());
        assertTrue(validation.issues().stream().anyMatch(issue -> "warning".equals(issue.severity())
            && "unreachable_node".equals(issue.code()) && "jump".equals(issue.nodeId())));
    }

    private static NodeGraphData graph(boolean withJump) {
        List<NodeGraphData.NodeData> nodes = new ArrayList<>();
        nodes.add(new NodeGraphData.NodeData("start", NodeType.START, null, 0, 0, new ArrayList<>()));
        if (withJump) nodes.add(new NodeGraphData.NodeData("jump", NodeType.JUMP, null, 100, 0, new ArrayList<>()));
        return new NodeGraphData(nodes, new ArrayList<>());
    }
}

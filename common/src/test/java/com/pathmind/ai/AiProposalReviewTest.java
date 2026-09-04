package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProposalReviewTest {
    @Test
    void diffAndPreviewIncludeNestedAttachments() {
        NodeGraphData.NodeData start = node("start", NodeType.START);
        NodeGraphData.NodeData control = node("control", NodeType.CONTROL_FOREVER);
        NodeGraphData.NodeData action = node("jump", NodeType.JUMP);
        control.setAttachedActionId("jump");
        action.setParentActionControlId("control");
        NodeGraphData graph = new NodeGraphData(new ArrayList<>(List.of(start, control, action)),
            new ArrayList<>(List.of(new NodeGraphData.ConnectionData("start", "control", 0, 0))));

        AiProposalReview review = AiProposalReview.create(null, graph);

        assertTrue(review.changes().stream().anyMatch(line -> line.contains("attachment control action → jump")));
        assertTrue(review.executionPaths().stream().anyMatch(line -> line.contains("{") && line.contains("[jump]")));
    }

    private static NodeGraphData.NodeData node(String id, NodeType type) {
        return new NodeGraphData.NodeData(id, type, null, 0, 0, new ArrayList<>());
    }
}

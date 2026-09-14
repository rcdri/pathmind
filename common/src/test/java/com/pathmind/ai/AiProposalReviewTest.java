package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void craftReviewUsesApplicationReadbackValues() {
        JsonArray commands = new JsonArray();
        commands.add(command("add_node", "ref", "craft", "nodeType", "CRAFT"));
        JsonObject set = command("set_parameters", "ref", "craft");
        JsonArray values = new JsonArray();
        values.add(command("value", "parameterId", "Item", "value", "minecraft:oak_planks"));
        values.add(command("value", "parameterId", "Amount", "value", "4"));
        set.add("parameterValues", values);
        commands.add(set);
        AiGraphCommandEngine.Result built = AiGraphCommandEngine.apply(emptyGraph(), commands, Map.of(), true, true);

        AiProposalReview review = AiProposalReview.create(null,
            NodeGraphPersistence.parseNodeGraphData(built.graph().toString()));

        assertTrue(review.changes().stream().anyMatch(line -> line.contains("Item=minecraft:oak_planks") && line.contains("Amount=4")));
    }

    private static NodeGraphData.NodeData node(String id, NodeType type) {
        return new NodeGraphData.NodeData(id, type, null, 0, 0, new ArrayList<>());
    }

    private static JsonObject command(String kind, Object... fields) {
        JsonObject command = new JsonObject();
        command.addProperty("kind", kind);
        for (int index = 0; index < fields.length; index += 2) command.addProperty(
            String.valueOf(fields[index]), String.valueOf(fields[index + 1]));
        return command;
    }

    private static JsonObject emptyGraph() {
        JsonObject graph = new JsonObject();
        graph.add("nodes", new JsonArray());
        graph.add("connections", new JsonArray());
        graph.add("customNodeDefinition", null);
        graph.add("routines", new JsonArray());
        return graph;
    }
}

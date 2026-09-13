package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeType;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGraphIntegrityValidatorTest {
    @Test
    void acceptsAWellFormedGenericFlowGraph() {
        NodeGraphData graph = graph(
            List.of(node("start", NodeType.START), node("jump", NodeType.JUMP)),
            List.of(new NodeGraphData.ConnectionData("start", "jump", 0, 0))
        );

        assertTrue(AiGraphIntegrityValidator.validate(graph, true, true).isEmpty());
    }

    @Test
    void acceptsPathmindsExistingExampleGraphs() throws Exception {
        for (int index = 1; index <= 3; index++) {
            String resource = "/assets/pathmind/onboarding_presets/example_" + index + ".json";
            try (var stream = AiGraphIntegrityValidatorTest.class.getResourceAsStream(resource)) {
                assertTrue(stream != null, "Missing " + resource);
                NodeGraphData graph = com.pathmind.data.NodeGraphPersistence.parseNodeGraphData(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                List<String> issues = AiGraphIntegrityValidator.validate(graph, true, true);
                assertTrue(issues.isEmpty(), resource + ": " + issues);
            }
        }
    }

    @Test
    void rejectsDataThatPersistenceWouldOtherwiseNormalizeAway() {
        NodeGraphData graph = graph(
            List.of(node("same", NodeType.START), node("same", NodeType.JUMP)),
            List.of(new NodeGraphData.ConnectionData("same", "missing", 0, 0))
        );

        List<String> issues = AiGraphIntegrityValidator.validate(graph, true, true);

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("duplicated")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("missing input node")));
    }

    @Test
    void rejectsModesAndAttachmentsUsingGenericRuntimeContracts() {
        NodeGraphData.NodeData control = node("control", NodeType.CONTROL_FOREVER);
        control.setMode(NodeMode.WAIT_SECONDS);
        control.setAttachedActionId("jump");
        NodeGraphData graph = graph(List.of(control, node("jump", NodeType.JUMP)), List.of());

        List<String> issues = AiGraphIntegrityValidator.validate(graph, true, true);

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("mode WAIT_SECONDS")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("not bidirectional")));
    }

    @Test
    void acceptsPersistedInternalModeForFixedBehaviorNodes() {
        NodeGraphData.NodeData open = node("open", NodeType.OPEN_INVENTORY);
        open.setMode(NodeMode.PLAYER_GUI_OPEN);

        assertTrue(AiGraphIntegrityValidator.validate(graph(List.of(open), List.of()), true, true).isEmpty());
    }

    @Test
    void rejectsMissingRequiredAttachmentAndWrongCatalogParameterType() {
        NodeGraphData.NodeData control = node("control", NodeType.CONTROL_FOREVER);
        NodeGraphData.NodeData wait = node("wait", NodeType.WAIT);
        wait.setMode(NodeMode.WAIT_SECONDS);
        NodeGraphData.ParameterData duration = new NodeGraphData.ParameterData("Duration", "1", "STRING");
        duration.setId("duration");
        wait.getParameters().add(duration);
        NodeGraphData graph = graph(List.of(control, wait), List.of());

        List<String> issues = AiGraphIntegrityValidator.validate(graph, true, true);

        assertTrue(issues.stream().anyMatch(issue -> issue.contains("requires an action attachment")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("wrong type")));
    }

    private static NodeGraphData graph(List<NodeGraphData.NodeData> nodes,
                                       List<NodeGraphData.ConnectionData> connections) {
        NodeGraphData graph = new NodeGraphData(new ArrayList<>(nodes), new ArrayList<>(connections));
        graph.setRoutines(new ArrayList<>());
        return graph;
    }

    private static NodeGraphData.NodeData node(String id, NodeType type) {
        return new NodeGraphData.NodeData(id, type, null, 0, 0, new ArrayList<>());
    }
}

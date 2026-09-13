package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeConnection;
import com.pathmind.nodes.NodeType;
import com.pathmind.routines.RoutineBuilderModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExecutionPreviewTest {
    @Test
    void expandsRepeatBodiesAndTrueFalseBranches() {
        JsonArray commands = new JsonArray();
        commands.add(add("start", "START"));
        commands.add(add("repeat", "CONTROL_REPEAT"));
        commands.add(add("jump", "JUMP"));
        commands.add(add("branch", "CONTROL_IF_ELSE"));
        commands.add(add("sensor", "SENSOR_IS_DAYTIME"));
        commands.add(add("yes", "MESSAGE"));
        commands.add(add("no", "WAIT"));
        commands.add(set("repeat", "count", "3"));
        commands.add(connect("start", "repeat", 0));
        commands.add(attach("attach_action", "repeat", "jump", null));
        commands.add(connect("repeat", "branch", 0));
        commands.add(attach("attach_sensor", "branch", "sensor", null));
        commands.add(connect("branch", "yes", 0));
        commands.add(connect("branch", "no", 1));
        AiGraphCommandEngine.Result graph = AiGraphCommandEngine.apply(empty(), commands, Map.of(), true, true);

        JsonObject preview = AiExecutionPreview.preview(
            com.pathmind.data.NodeGraphPersistence.parseNodeGraphData(graph.graph().toString()));
        String paths = preview.getAsJsonArray("paths").toString();

        assertEquals(2, preview.getAsJsonArray("paths").size());
        assertTrue(paths.contains("JUMP"));
        assertTrue(paths.contains("true"));
        assertTrue(paths.contains("false"));
    }

    @Test
    void reportsVariableAssignmentsAndConsumers() {
        JsonArray commands = new JsonArray();
        commands.add(add("start", "START"));
        commands.add(add("amount", "PARAM_AMOUNT"));
        commands.add(declareVariable());
        commands.add(add("look", "LOOK"));
        commands.add(add("scoreRead", "VARIABLE"));
        commands.add(set("scoreRead", "variable", "score"));
        commands.add(attach("attach_parameter", "look", "scoreRead", 0));
        commands.add(connect("start", "setScore", 0));
        commands.add(connect("setScore", "look", 0));
        AiGraphCommandEngine.Result graph = AiGraphCommandEngine.apply(empty(), commands, Map.of(), true, true);

        JsonObject preview = AiExecutionPreview.preview(
            com.pathmind.data.NodeGraphPersistence.parseNodeGraphData(graph.graph().toString()));
        JsonObject dependency = preview.getAsJsonArray("variableDependencies").get(0).getAsJsonObject();

        assertEquals("score", dependency.get("variable").getAsString());
        assertEquals(1, dependency.getAsJsonArray("assignedBy").size());
        assertEquals(1, dependency.getAsJsonArray("consumedBy").size());
    }

    @Test
    void expandsRoutineCallsIntoTheirDefinitionPaths() {
        NodeGraphData.RoutineDefinitionData routine = RoutineBuilderModel.createRoutine("Hop");
        Node entry = NodeGraphPersistence.convertToNodes(routine.getGraph()).stream()
            .filter(node -> node.getType() == NodeType.ROUTINE_ENTRY).findFirst().orElseThrow();
        Node jump = new Node(NodeType.JUMP, 180, 0);
        routine.setGraph(NodeGraphPersistence.createGraphData(List.of(entry, jump),
            List.of(new NodeConnection(entry, jump, 0, 0))));
        Node start = new Node(NodeType.START, 0, 0);
        Node call = Node.createRoutineCall(routine, 180, 0);
        NodeGraphData graph = NodeGraphPersistence.createGraphData(List.of(start, call),
            List.of(new NodeConnection(start, call, 0, 0)));
        graph.setRoutines(new java.util.ArrayList<>(List.of(routine)));

        String paths = AiExecutionPreview.preview(graph).getAsJsonArray("paths").toString();

        assertTrue(paths.contains("routine:"));
        assertTrue(paths.contains("Hop"));
        assertTrue(paths.contains("ROUTINE_ENTRY"));
        assertTrue(paths.contains("JUMP"));
    }

    private static JsonObject declareVariable() {
        JsonObject value = base("declare_variable");
        value.addProperty("ref", "setScore");
        value.addProperty("variableRef", "scoreTarget");
        value.addProperty("name", "score");
        value.addProperty("child", "amount");
        return value;
    }

    private static JsonObject add(String ref, String type) {
        JsonObject value = base("add_node");
        value.addProperty("ref", ref);
        value.addProperty("nodeType", type);
        return value;
    }

    private static JsonObject set(String ref, String parameter, String newValue) {
        JsonObject value = base("set_parameter");
        value.addProperty("ref", ref);
        value.addProperty("parameterId", parameter);
        value.addProperty("value", newValue);
        return value;
    }

    private static JsonObject connect(String from, String to, int outputSocket) {
        JsonObject value = base("connect");
        value.addProperty("from", from);
        value.addProperty("to", to);
        value.addProperty("outputSocket", outputSocket);
        value.addProperty("inputSocket", 0);
        return value;
    }

    private static JsonObject attach(String kind, String host, String child, Integer slot) {
        JsonObject value = base(kind);
        value.addProperty("host", host);
        value.addProperty("child", child);
        if (slot != null) value.addProperty("slotIndex", slot);
        return value;
    }

    private static JsonObject base(String kind) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", kind);
        return value;
    }

    private static JsonObject empty() {
        JsonObject value = new JsonObject();
        value.add("nodes", new JsonArray());
        value.add("connections", new JsonArray());
        value.add("customNodeDefinition", null);
        value.add("routines", new JsonArray());
        return value;
    }
}

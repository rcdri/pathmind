package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGraphQueryEngineTest {
    @Test
    void findsNodesByTypeAndParameterTextWithoutReturningFullGraph() {
        AiGraphCommandEngine.Result graph = buildGraph();
        JsonArray types = new JsonArray();
        types.add("WAIT");

        JsonObject result = AiGraphQueryEngine.find(graph.graph(), types, "2.5", graph.references());

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(1, result.getAsJsonArray("nodes").size());
        assertEquals("pause", result.getAsJsonArray("nodes").get(0).getAsJsonObject().get("ref").getAsString());
        assertFalse(result.has("graph"));
    }

    @Test
    void inspectsOnlyTheRequestedNeighborhood() {
        AiGraphCommandEngine.Result graph = buildGraph();
        JsonArray refs = new JsonArray();
        refs.add("pause");

        JsonObject result = AiGraphQueryEngine.inspectSubgraph(graph.graph(), refs, 1, graph.references());

        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(3, result.getAsJsonArray("nodes").size());
        assertEquals(2, result.getAsJsonArray("connections").size());
        assertFalse(result.has("routines"));
    }

    private static AiGraphCommandEngine.Result buildGraph() {
        JsonObject source = new JsonObject();
        source.add("nodes", new JsonArray());
        source.add("connections", new JsonArray());
        source.add("customNodeDefinition", null);
        source.add("routines", new JsonArray());
        JsonArray commands = new JsonArray();
        commands.add(add("start", "START"));
        commands.add(add("pause", "WAIT"));
        commands.add(set("pause", "duration", "2.5"));
        commands.add(add("jump", "JUMP"));
        commands.add(connect("start", "pause"));
        commands.add(connect("pause", "jump"));
        return AiGraphCommandEngine.apply(source, commands, Map.of(), true, true);
    }

    private static JsonObject add(String ref, String type) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "add_node");
        value.addProperty("ref", ref);
        value.addProperty("nodeType", type);
        return value;
    }

    private static JsonObject set(String ref, String parameter, String newValue) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "set_parameter");
        value.addProperty("ref", ref);
        value.addProperty("parameterId", parameter);
        value.addProperty("value", newValue);
        return value;
    }

    private static JsonObject connect(String from, String to) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "connect");
        value.addProperty("from", from);
        value.addProperty("to", to);
        value.addProperty("outputSocket", 0);
        value.addProperty("inputSocket", 0);
        return value;
    }
}

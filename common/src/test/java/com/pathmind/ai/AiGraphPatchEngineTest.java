package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGraphPatchEngineTest {
    @Test
    void appliesSmallPatchesToACopy() {
        JsonObject source = graph();
        JsonArray operations = new JsonArray();
        operations.add(operation("add", "/nodes/-", "{\"id\":\"start\"}"));

        AiGraphPatchEngine.Result result = AiGraphPatchEngine.apply(source, operations);

        assertTrue(result.success());
        assertEquals(0, source.getAsJsonArray("nodes").size());
        assertEquals("start", result.graph().getAsJsonArray("nodes").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void failedPatchIsAtomicAndCannotEscapeGraph() {
        JsonObject source = graph();
        JsonArray operations = new JsonArray();
        operations.add(operation("add", "/nodes/-", "{\"id\":\"temporary\"}"));
        operations.add(operation("replace", "/secret", "true"));

        AiGraphPatchEngine.Result result = AiGraphPatchEngine.apply(source, operations);

        assertFalse(result.success());
        assertEquals(0, source.getAsJsonArray("nodes").size());
    }

    private static JsonObject graph() {
        JsonObject graph = new JsonObject();
        graph.add("nodes", new JsonArray());
        graph.add("connections", new JsonArray());
        graph.add("customNodeDefinition", null);
        graph.add("routines", new JsonArray());
        return graph;
    }

    private static JsonObject operation(String op, String path, String valueJson) {
        JsonObject operation = new JsonObject();
        operation.addProperty("op", op);
        operation.addProperty("path", path);
        operation.addProperty("valueJson", valueJson);
        return operation;
    }
}

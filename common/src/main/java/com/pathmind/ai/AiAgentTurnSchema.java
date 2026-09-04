package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.nodes.NodeType;

/** Strict envelope used for one model-selected action in the local graph agent loop. */
public final class AiAgentTurnSchema {
    private AiAgentTurnSchema() {
    }

    public static JsonObject create() {
        JsonObject operation = object(
            property("op", enumString("add", "remove", "replace")),
            property("path", string()),
            property("valueJson", nullable(string()))
        );
        return object(
            property("tool", enumString("select_target", "inspect_preset", "list_node_types", "describe_node_types",
                "list_examples", "inspect_example", "apply_graph_patch", "validate_graph", "preview_execution", "finish")),
            property("target", nullable(enumString("new", "current", "inspect"))),
            property("nodeTypes", array(enumValues(NodeType.values()))),
            property("exampleId", nullable(string())),
            property("draftRevision", nullable(integer())),
            property("operations", array(operation)),
            property("title", nullable(string())),
            property("response", nullable(string())),
            property("workLog", array(string()))
        );
    }

    private static Property property(String name, JsonObject schema) { return new Property(name, schema); }

    private static JsonObject object(Property... properties) {
        JsonObject schema = typed("object");
        JsonObject values = new JsonObject();
        JsonArray required = new JsonArray();
        for (Property property : properties) {
            values.add(property.name(), property.schema());
            required.add(property.name());
        }
        schema.add("properties", values);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject array(JsonObject item) {
        JsonObject schema = typed("array");
        schema.add("items", item);
        return schema;
    }

    private static JsonObject nullable(JsonObject value) {
        JsonArray options = new JsonArray();
        options.add(value);
        options.add(typed("null"));
        JsonObject schema = new JsonObject();
        schema.add("anyOf", options);
        return schema;
    }

    private static JsonObject enumString(String... values) {
        JsonObject schema = typed("string");
        JsonArray allowed = new JsonArray();
        for (String value : values) allowed.add(value);
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject enumValues(Enum<?>[] values) {
        JsonObject schema = typed("string");
        JsonArray allowed = new JsonArray();
        for (Enum<?> value : values) allowed.add(value.name());
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject string() { return typed("string"); }
    private static JsonObject integer() { return typed("integer"); }

    private static JsonObject typed(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private record Property(String name, JsonObject schema) { }
}

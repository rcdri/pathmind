package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeType;
import com.pathmind.nodes.NodeValueTrait;
import com.pathmind.nodes.ParameterType;
import com.pathmind.nodes.RuntimeValueScope;
import com.pathmind.nodes.StartLaunchMode;
import com.pathmind.nodes.StartScreenTarget;
import com.pathmind.routines.RoutineValueKind;

/** JSON Schema shared by provider adapters that support strict structured output. */
public final class AiPresetResponseSchema {
    private AiPresetResponseSchema() {
    }

    public static JsonObject create() {
        JsonObject root = object(
            property("target", enumString("new", "current", "inspect")),
            property("title", string()),
            property("response", string()),
            property("workLog", array(string())),
            property("graph", nullable(ref("#/$defs/graph")))
        );
        JsonObject definitions = new JsonObject();
        definitions.add("graph", graph());
        definitions.add("node", node());
        definitions.add("connection", connection());
        definitions.add("parameter", parameter());
        definitions.add("parameterAttachment", parameterAttachment());
        definitions.add("routineArgument", routineArgument());
        definitions.add("routine", routine());
        definitions.add("routineInput", routineInput());
        definitions.add("customNodeDefinition", customNodeDefinition());
        definitions.add("customNodePort", customNodePort());
        root.add("$defs", definitions);
        return root;
    }

    private static JsonObject graph() {
        return object(
            property("nodes", array(ref("#/$defs/node"))),
            property("connections", array(ref("#/$defs/connection"))),
            property("customNodeDefinition", nullable(ref("#/$defs/customNodeDefinition"))),
            property("routines", array(ref("#/$defs/routine")))
        );
    }

    private static JsonObject node() {
        return object(
            property("id", string()),
            property("type", enumValues(NodeType.values())),
            property("mode", nullable(enumValues(NodeMode.values()))),
            property("x", integer()), property("y", integer()),
            property("parameters", array(ref("#/$defs/parameter"))),
            property("attachedSensorId", nullable(string())),
            property("parentControlId", nullable(string())),
            property("attachedActionId", nullable(string())),
            property("parentActionControlId", nullable(string())),
            property("attachedParameterId", nullable(string())),
            property("parentParameterHostId", nullable(string())),
            property("parameterAttachments", array(ref("#/$defs/parameterAttachment"))),
            property("booleanToggleValue", nullable(bool())),
            property("parameterSlotCount", nullable(integer())),
            property("startNodeNumber", nullable(integer())),
            property("startLaunchMode", nullable(startLaunchModes())),
            property("startScreenTarget", nullable(startScreenTargets())),
            property("runtimeSourceNodeId", nullable(string())),
            property("runtimeValueScope", nullable(enumValues(RuntimeValueScope.values()))),
            property("routineId", nullable(string())),
            property("routineInputId", nullable(string())),
            property("routineArguments", array(ref("#/$defs/routineArgument"))),
            property("messageLines", nullable(array(string()))),
            property("messageClientSide", nullable(bool())),
            property("bookText", nullable(string())),
            property("stickyNoteText", nullable(string())),
            property("stickyNoteWidth", nullable(integer())),
            property("stickyNoteHeight", nullable(integer())),
            property("gotoAllowBreakWhileExecuting", nullable(bool())),
            property("gotoAllowPlaceWhileExecuting", nullable(bool())),
            property("keyPressedActivatesInGuis", nullable(bool())),
            property("templateName", nullable(string())),
            property("templateVersion", nullable(integer())),
            property("templateGraph", nullable(ref("#/$defs/graph")))
        );
    }

    private static JsonObject connection() {
        return object(
            property("outputNodeId", string()), property("inputNodeId", string()),
            property("outputSocket", integer()), property("inputSocket", integer())
        );
    }

    private static JsonObject parameter() {
        return object(
            property("id", nullable(string())), property("name", string()),
            property("value", nullable(string())), property("type", enumValues(ParameterType.values())),
            property("userEdited", nullable(bool()))
        );
    }

    private static JsonObject parameterAttachment() {
        return object(
            property("slotIndex", integer()), property("parameterNodeId", string()),
            property("routineInputId", nullable(string()))
        );
    }

    private static JsonObject routineArgument() {
        return object(
            property("inputId", nullable(string())), property("label", nullable(string())),
            property("valueKind", nullable(enumValues(RoutineValueKind.values()))), property("required", nullable(bool())),
            property("defaultValue", nullable(string())), property("orphaned", nullable(bool()))
        );
    }

    private static JsonObject routine() {
        return object(
            property("id", string()), property("name", string()),
            property("interfaceVersion", nullable(integer())),
            property("implementationRevision", nullable(integer())),
            property("interfaceSignature", nullable(string())),
            property("implementationSignature", nullable(string())),
            property("libraryRoutineId", nullable(string())),
            property("inputs", array(ref("#/$defs/routineInput"))),
            property("graph", ref("#/$defs/graph"))
        );
    }

    private static JsonObject routineInput() {
        return object(
            property("id", string()), property("label", string()),
            property("valueKind", enumValues(RoutineValueKind.values())), property("acceptedTraits", array(enumValues(NodeValueTrait.values()))),
            property("required", nullable(bool())), property("defaultValue", nullable(string())),
            property("order", nullable(integer()))
        );
    }

    private static JsonObject customNodeDefinition() {
        return object(
            property("presetName", nullable(string())), property("name", nullable(string())),
            property("version", nullable(integer())), property("signature", nullable(string())),
            property("inputs", array(ref("#/$defs/customNodePort"))),
            property("outputs", array(ref("#/$defs/customNodePort")))
        );
    }

    private static JsonObject customNodePort() {
        return object(
            property("name", string()), property("type", string()),
            property("defaultValue", nullable(string()))
        );
    }

    private static Property property(String name, JsonObject schema) {
        return new Property(name, schema);
    }

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

    private static JsonObject startLaunchModes() {
        JsonObject schema = typed("string");
        JsonArray allowed = new JsonArray();
        for (StartLaunchMode value : StartLaunchMode.values()) allowed.add(value.getId());
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject startScreenTargets() {
        JsonObject schema = typed("string");
        JsonArray allowed = new JsonArray();
        for (StartScreenTarget value : StartScreenTarget.values()) allowed.add(value.getId());
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject ref(String target) {
        JsonObject schema = new JsonObject();
        schema.addProperty("$ref", target);
        return schema;
    }

    private static JsonObject string() { return typed("string"); }
    private static JsonObject integer() { return typed("integer"); }
    private static JsonObject bool() { return typed("boolean"); }
    private static JsonObject typed(String type) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        return schema;
    }

    private record Property(String name, JsonObject schema) {
    }
}

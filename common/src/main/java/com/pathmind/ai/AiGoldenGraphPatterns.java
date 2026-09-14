package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.data.NodeGraphData;
import com.pathmind.data.NodeGraphPersistence;
import java.util.Map;

/** Builds small golden graphs through the same semantic command layer exposed to the model. */
final class AiGoldenGraphPatterns {
    private AiGoldenGraphPatterns() {
    }

    static NodeGraphData conditionWithSensor() {
        return build(
            sequence(new String[] {"start", "condition", "after"}, new String[] {"START", "CONTROL_IF_DO", "MESSAGE"}),
            command("add_node", "ref", "sensor", "nodeType", "SENSOR_IS_DAYTIME"),
            command("add_node", "ref", "action", "nodeType", "JUMP"),
            command("attach_sensor", "host", "condition", "child", "sensor"),
            command("attach_action", "host", "condition", "child", "action"),
            command("auto_layout")
        );
    }

    static NodeGraphData repeatUntil() {
        return build(
            sequence(new String[] {"start", "repeatUntil", "after"},
                new String[] {"START", "CONTROL_REPEAT_UNTIL", "MESSAGE"}),
            command("add_node", "ref", "sensor", "nodeType", "SENSOR_IS_DAYTIME"),
            command("add_node", "ref", "action", "nodeType", "JUMP"),
            command("attach_sensor", "host", "repeatUntil", "child", "sensor"),
            command("attach_action", "host", "repeatUntil", "child", "action"),
            command("auto_layout")
        );
    }

    static NodeGraphData nestedControls() {
        return build(
            sequence(new String[] {"start", "repeat", "after"}, new String[] {"START", "CONTROL_REPEAT", "MESSAGE"}),
            command("set_parameter", "ref", "repeat", "parameterId", "count", "value", "3"),
            command("add_node", "ref", "condition", "nodeType", "CONTROL_IF_DO"),
            command("add_node", "ref", "sensor", "nodeType", "SENSOR_IS_DAYTIME"),
            command("add_node", "ref", "action", "nodeType", "JUMP"),
            command("attach_action", "host", "repeat", "child", "condition"),
            command("attach_sensor", "host", "condition", "child", "sensor"),
            command("attach_action", "host", "condition", "child", "action"),
            command("auto_layout")
        );
    }

    static NodeGraphData routineWithArguments() {
        JsonObject routine = command("create_routine", "ref", "call", "routineRef", "lookRoutine",
            "name", "Look With Rotation");
        routine.add("refs", strings("look"));
        JsonObject input = new JsonObject();
        input.addProperty("label", "rotation");
        input.addProperty("valueKind", "ROTATION");
        input.addProperty("required", false);
        input.addProperty("defaultValue", "0,0");
        input.addProperty("bindToRef", "look");
        input.addProperty("slotIndex", 0);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        routine.add("routineInputs", inputs);
        return build(
            sequence(new String[] {"start", "look", "after"}, new String[] {"START", "LOOK", "MESSAGE"}),
            routine,
            command("add_routine_call", "ref", "secondCall", "routineRef", "lookRoutine"),
            command("connect", "from", "after", "to", "secondCall", "outputSocket", 0, "inputSocket", 0),
            command("auto_layout")
        );
    }

    static NodeGraphData inventoryWorkflow() {
        return build(
            sequence(new String[] {"start", "open", "hotbar", "close"},
                new String[] {"START", "OPEN_INVENTORY", "HOTBAR", "CLOSE_GUI"}),
            command("add_node", "ref", "slot", "nodeType", "PARAM_INVENTORY_SLOT"),
            command("attach_parameter", "host", "hotbar", "child", "slot", "slotIndex", 0),
            command("auto_layout")
        );
    }

    static NodeGraphData configuredInventoryAction() {
        JsonObject configuration = command("configure_node", "ref", "craft", "mode", "CRAFT_PLAYER_GUI");
        JsonArray values = new JsonArray();
        JsonObject amount = new JsonObject();
        amount.addProperty("parameterId", "amount"); amount.addProperty("value", "4");
        values.add(amount); configuration.add("parameterValues", values);
        return build(
            sequence(new String[] {"start", "open", "craft", "close"},
                new String[] {"START", "OPEN_INVENTORY", "CRAFT", "CLOSE_GUI"}),
            command("add_node", "ref", "item", "nodeType", "PARAM_ITEM"),
            command("set_parameter", "ref", "item", "parameterId", "item", "value", "minecraft:oak_planks"),
            configuration,
            command("attach_parameter", "host", "craft", "child", "item", "slotIndex", 0),
            command("auto_layout")
        );
    }

    static NodeGraphData navigationAndCollection() {
        return build(
            sequence(new String[] {"start", "goto", "collect", "after"},
                new String[] {"START", "GOTO", "COLLECT", "MESSAGE"}),
            command("add_node", "ref", "destination", "nodeType", "PARAM_COORDINATE"),
            command("add_node", "ref", "block", "nodeType", "PARAM_BLOCK"),
            command("set_parameter", "ref", "collect", "parameterId", "amount", "value", "16"),
            command("attach_parameter", "host", "goto", "child", "destination", "slotIndex", 0),
            command("attach_parameter", "host", "collect", "child", "block", "slotIndex", 0),
            command("auto_layout")
        );
    }

    private static NodeGraphData build(JsonObject... commands) {
        JsonArray batch = new JsonArray();
        for (JsonObject command : commands) batch.add(command);
        AiGraphCommandEngine.Result result = AiGraphCommandEngine.apply(emptyGraph(), batch, Map.of(), true, true);
        if (!result.success()) throw new IllegalStateException("Golden graph construction failed: " + result.message());
        NodeGraphData graph = NodeGraphPersistence.parseNodeGraphData(result.graph().toString());
        if (graph == null) throw new IllegalStateException("Golden graph construction returned no graph.");
        return graph;
    }

    private static JsonObject sequence(String[] refs, String[] nodeTypes) {
        JsonObject command = command("add_sequence");
        command.add("refs", strings(refs));
        command.add("nodeTypes", strings(nodeTypes));
        return command;
    }

    private static JsonObject command(String kind, Object... fields) {
        JsonObject command = new JsonObject();
        command.addProperty("kind", kind);
        for (int index = 0; index < fields.length; index += 2) {
            String key = (String) fields[index];
            Object value = fields[index + 1];
            if (value instanceof Number number) command.addProperty(key, number);
            else if (value instanceof Boolean bool) command.addProperty(key, bool);
            else command.addProperty(key, String.valueOf(value));
        }
        return command;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
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

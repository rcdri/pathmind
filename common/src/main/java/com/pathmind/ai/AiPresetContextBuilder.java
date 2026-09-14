package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeType;

/** Produces the small, durable node contract sent with a preset request. */
public final class AiPresetContextBuilder {
    private AiPresetContextBuilder() {
    }

    public static String systemPrompt(boolean baritoneAvailable, boolean uiUtilsAvailable) {
        JsonArray nodes = availableNodeContracts(baritoneAvailable, uiUtilsAvailable);
        JsonObject contract = new JsonObject();
        contract.add("availableNodes", nodes);
        contract.addProperty("baritoneAvailable", baritoneAvailable);
        contract.addProperty("uiUtilsAvailable", uiUtilsAvailable);
        contract.add("attachmentRules", attachmentRules());
        return "You are Pathmind's preset architect. Create small, understandable Minecraft automation graphs. "
            + "Use only the node types in this contract. Never invent nodes or parameters. A root graph needs a START node. "
            + "Return JSON only with target, title, response, workLog, and graph. Use graph:null for inspect. "
            + "For a graph, include nodes, connections, customNodeDefinition, and routines; use null or [] for unused fields. "
            + "Every node needs a unique id, type, x, and y. Connections use outputNodeId/outputSocket/inputNodeId/inputSocket. "
            + "Use only parameters and modes listed for that node. Parameter-host, sensor-host, and action-host relationships must follow attachmentRules and be bidirectional. "
            + "Honor slot requiredness and acceptedTraits. A normal connection is control flow; nested sensor, action, and value relationships are attachments, not connections. "
            + "For inspect requests, address every supplied validation issue before answering. Keep response to at most two short sentences and workLog to at most four short lines. "
            + "The graph must use Pathmind's serialized NodeGraphData shape and all destructive world actions must be obvious in the description.\n"
            + contract;
    }

    public static JsonArray availableNodeContracts(boolean baritoneAvailable, boolean uiUtilsAvailable) {
        JsonArray nodes = new JsonArray();
        for (NodeType type : NodeType.values()) {
            if (!NodeCatalog.hasDefinition(type) || !NodeCatalog.shouldDisplayInSidebar(type, baritoneAvailable, uiUtilsAvailable)) continue;
            nodes.add(nodeContract(type));
        }
        return nodes;
    }

    public static JsonObject nodeContract(NodeType type) {
        if (type == null || !NodeCatalog.hasDefinition(type)) return null;
        JsonObject node = new JsonObject();
        node.addProperty("type", type.name());
        node.addProperty("name", NodeCatalog.displayName(type));
        node.addProperty("description", NodeCatalog.description(type));
        node.addProperty("category", NodeCatalog.category(type).name());
        Node specimen = new Node(type, 0, 0);
        node.addProperty("inputSockets", specimen.getInputSocketCount());
        node.addProperty("outputSockets", specimen.getOutputSocketCount());
        node.addProperty("defaultMode", specimen.getMode() == null ? "" : specimen.getMode().name());
        node.addProperty("acceptsSensor", specimen.hasSensorSlot());
        node.addProperty("acceptsAction", specimen.hasActionSlot());
        node.addProperty("sensorRequired", specimen.hasSensorSlot());
        node.addProperty("actionRequired", specimen.hasActionSlot());
        node.addProperty("isBooleanSensor", specimen.isSensorNode());
        node.addProperty("isParameterValue", specimen.isParameterNode());
        JsonArray providedTraits = new JsonArray();
        NodeCatalog.providedTraits(type).forEach(trait -> providedTraits.add(trait.name()));
        node.add("providedTraits", providedTraits);
        node.add("parameters", parameters(specimen));
        node.add("parameterSlots", parameterSlots(specimen));
        JsonArray modes = new JsonArray();
        for (NodeMode mode : NodeMode.getModesForNodeType(type)) {
            Node modeSpecimen = new Node(type, 0, 0);
            modeSpecimen.setMode(mode);
            JsonObject modeContract = new JsonObject();
            modeContract.addProperty("value", mode.name());
            modeContract.addProperty("name", mode.getDisplayName());
            modeContract.addProperty("description", mode.getDescription());
            modeContract.add("parameters", parameters(modeSpecimen));
            modeContract.add("parameterSlots", parameterSlots(modeSpecimen));
            modes.add(modeContract);
        }
        node.add("modes", modes);
        node.add("attachmentRules", attachmentRules());
        return node;
    }

    private static JsonObject attachmentRules() {
        JsonObject attachments = new JsonObject();
        attachments.addProperty("sensor", "Host attachedSensorId and child parentControlId must reference each other.");
        attachments.addProperty("action", "Host attachedActionId and child parentActionControlId must reference each other.");
        attachments.addProperty("parameter", "Host parameterAttachments entries and child parentParameterHostId must reference each other; slotIndex must match the host slot contract.");
        return attachments;
    }

    private static JsonArray parameters(Node specimen) {
        JsonArray parameters = new JsonArray();
        for (NodeParameter parameter : specimen.getParameters()) {
            JsonObject parameterContract = new JsonObject();
            parameterContract.addProperty("id", parameter.getId());
            parameterContract.addProperty("name", parameter.getName());
            parameterContract.addProperty("type", parameter.getType().name());
            parameterContract.addProperty("default", parameter.getDefaultValue());
            parameterContract.add("valueContract", new com.google.gson.Gson().toJsonTree(parameter.getValueContract()));
            parameters.add(parameterContract);
        }
        return parameters;
    }

    private static JsonArray parameterSlots(Node specimen) {
        JsonArray slots = new JsonArray();
        for (int index = 0; index < specimen.getParameterSlotCount(); index++) {
            JsonObject slot = new JsonObject();
            slot.addProperty("index", index);
            slot.addProperty("label", specimen.getParameterSlotLabel(index));
            slot.addProperty("required", specimen.isParameterSlotRequired(index));
            JsonArray acceptedTraits = new JsonArray();
            specimen.getAcceptedTraitsForParameterSlot(index).forEach(trait -> acceptedTraits.add(trait.name()));
            slot.add("acceptedTraits", acceptedTraits);
            slot.addProperty("valueSource", "An attached value node supplies this slot at runtime; do not assume the host's literal fields are effective values.");
            slots.add(slot);
        }
        return slots;
    }
}

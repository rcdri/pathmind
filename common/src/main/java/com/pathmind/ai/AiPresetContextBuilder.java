package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pathmind.nodes.NodeCatalog;
import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeType;

/** Produces the small, durable node contract sent with a preset request. */
public final class AiPresetContextBuilder {
    private AiPresetContextBuilder() {
    }

    public static String systemPrompt(boolean baritoneAvailable, boolean uiUtilsAvailable) {
        JsonArray nodes = new JsonArray();
        for (NodeType type : NodeType.values()) {
            if (!NodeCatalog.hasDefinition(type) || !NodeCatalog.shouldDisplayInSidebar(type, baritoneAvailable, uiUtilsAvailable)) continue;
            JsonObject node = new JsonObject();
            node.addProperty("type", type.name());
            node.addProperty("name", NodeCatalog.displayName(type));
            node.addProperty("description", NodeCatalog.description(type));
            node.addProperty("category", NodeCatalog.category(type).name());
            Node specimen = new Node(type, 0, 0);
            node.addProperty("inputSockets", specimen.getInputSocketCount());
            node.addProperty("outputSockets", specimen.getOutputSocketCount());
            node.addProperty("parameterSlots", NodeCatalog.parameterSlotCount(type));
            JsonArray parameters = new JsonArray();
            for (NodeParameter parameter : specimen.getParameters()) {
                JsonObject parameterContract = new JsonObject();
                parameterContract.addProperty("id", parameter.getId());
                parameterContract.addProperty("name", parameter.getName());
                parameterContract.addProperty("type", parameter.getType().name());
                parameterContract.addProperty("default", parameter.getDefaultValue());
                parameters.add(parameterContract);
            }
            node.add("parameters", parameters);
            nodes.add(node);
        }
        JsonObject contract = new JsonObject();
        contract.add("availableNodes", nodes);
        contract.addProperty("baritoneAvailable", baritoneAvailable);
        contract.addProperty("uiUtilsAvailable", uiUtilsAvailable);
        return "You are Pathmind's preset architect. Create small, understandable Minecraft automation graphs. "
            + "Use only the node types in this contract. Never invent nodes or parameters. A root graph needs a START node. "
            + "Return JSON only in this envelope: {\\\"target\\\":\\\"new\\\"|\\\"current\\\"|\\\"inspect\\\",\\\"title\\\":string,\\\"response\\\":string,\\\"workLog\\\":[string],\\\"graph\\\":{\\\"nodes\\\":[],\\\"connections\\\":[]}}. "
            + "Every node needs a unique id, type, x, and y. Connections use outputNodeId/outputSocket/inputNodeId/inputSocket. "
            + "Use only listed parameter names/types and include a parameter only when it has a non-default value. "
            + "Parameter-host nodes require separate parameter nodes and parameterAttachments. For example, a valid walk-then-jump graph has START -> WALK -> JUMP, and WALK has "
            + "parameterAttachments:[{\"slotIndex\":0,\"parameterNodeId\":\"walk-direction\"},{\"slotIndex\":1,\"parameterNodeId\":\"walk-duration\"}]. "
            + "The walk-direction node is type PARAM_DIRECTION with parentParameterHostId:\"walk\" and parameters [{\"id\":\"direction_mode\",\"name\":\"Mode\",\"value\":\"cardinal\",\"type\":\"STRING\"},{\"id\":\"direction_cardinal\",\"name\":\"Direction\",\"value\":\"north\",\"type\":\"STRING\"}]. "
            + "The walk-duration node is type PARAM_DURATION with parentParameterHostId:\"walk\", mode:\"WAIT_SECONDS\", and a Duration parameter. "
            + "CONTROL_REPEAT executes only its attached action: set repeat.attachedActionId to the child node id and child.parentActionControlId to the repeat id. Its normal output is after the loop, never the repeated action. "
            + "For inspect requests, check that rule and every supplied audit issue before answering. Keep response to at most two short sentences and workLog to at most four short lines. "
            + "The graph must use Pathmind's serialized NodeGraphData shape and all destructive world actions must be obvious in the description.\n"
            + contract;
    }
}

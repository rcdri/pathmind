package com.pathmind.ai;

import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeType;
import com.pathmind.nodes.ParameterType;

/** Adapter to the shared catalog value contract. No node-specific AI rules. */
final class AiParameterValidator {
    private AiParameterValidator() { }

    static CheckedValue validate(NodeType type, NodeMode mode, String parameterId, String value) {
        Node specimen = Node.createForEditor(type, 0, 0);
        if (mode != null) specimen.setMode(mode);
        String id = NodeParameter.createDefaultId(parameterId);
        NodeParameter parameter = specimen.getParameters().stream()
            .filter(candidate -> id.equals(candidate.getId())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(type + " has no parameter '" + parameterId + "' in mode " + specimen.getMode()));
        try {
            return new CheckedValue(parameter.getId(), parameter.getName(), parameter.getType(),
                parameter.getValueContract().validate(parameter.getType(), value));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(type + "." + id + ": " + failure.getMessage());
        }
    }

    record CheckedValue(String parameterId, String parameterName, ParameterType type, String value) { }
}

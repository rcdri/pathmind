package com.pathmind.ai;

import com.pathmind.nodes.Node;
import com.pathmind.nodes.NodeMode;
import com.pathmind.nodes.NodeParameter;
import com.pathmind.nodes.NodeType;
import com.pathmind.nodes.ParameterType;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validates and canonicalizes model-supplied values against real node parameter contracts. */
final class AiParameterValidator {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private AiParameterValidator() { }

    static CheckedValue validate(NodeType nodeType, NodeMode mode, String parameterId, String rawValue) {
        if (nodeType == null) throw new IllegalArgumentException("The parameter's node type is missing.");
        Node specimen = Node.createForEditor(nodeType, 0, 0);
        if (mode != null) specimen.setMode(mode);
        String normalizedId = NodeParameter.createDefaultId(parameterId);
        NodeParameter parameter = specimen.getParameters().stream()
            .filter(candidate -> parameterId.equals(candidate.getName()) || normalizedId.equals(candidate.getId()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException(
                nodeType + " has no parameter '" + parameterId + "' in mode " + specimen.getMode() + "."));
        String value = rawValue == null ? "" : rawValue;
        String canonical = switch (parameter.getType()) {
            case INTEGER -> canonicalInteger(value, nodeType, parameter);
            case DOUBLE -> canonicalDouble(value, nodeType, parameter);
            case BOOLEAN -> canonicalBoolean(value, nodeType, parameter);
            case BLOCK_TYPE -> canonicalResourceId(value, nodeType, parameter);
            default -> value;
        };
        if (nodeType == NodeType.CRAFT && "item".equals(parameter.getId())) {
            canonical = canonicalResourceId(value, nodeType, parameter);
        }
        return new CheckedValue(parameter.getId(), parameter.getName(), parameter.getType(), canonical);
    }

    private static String canonicalInteger(String value, NodeType nodeType, NodeParameter parameter) {
        String trimmed = value.trim();
        if (!trimmed.matches("-?[0-9]+")) throw invalid(nodeType, parameter, "a whole number", value);
        try {
            int parsed = Integer.parseInt(trimmed);
            if (nodeType == NodeType.CRAFT && "amount".equals(parameter.getId()) && parsed < 1) {
                throw invalid(nodeType, parameter, "a positive whole number", value);
            }
            return Integer.toString(parsed);
        } catch (NumberFormatException exception) {
            throw invalid(nodeType, parameter, "a 32-bit whole number", value);
        }
    }

    private static String canonicalDouble(String value, NodeType nodeType, NodeParameter parameter) {
        String trimmed = value.trim();
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) throw invalid(nodeType, parameter, "a finite number", value);
            return trimmed;
        } catch (NumberFormatException exception) {
            throw invalid(nodeType, parameter, "a number", value);
        }
    }

    private static String canonicalBoolean(String value, NodeType nodeType, NodeParameter parameter) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (!"true".equals(trimmed) && !"false".equals(trimmed)) {
            throw invalid(nodeType, parameter, "true or false", value);
        }
        return trimmed;
    }

    private static String canonicalResourceId(String value, NodeType nodeType, NodeParameter parameter) {
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        String canonical = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
        if (!RESOURCE_ID.matcher(canonical).matches()) {
            throw invalid(nodeType, parameter, "one item identifier such as minecraft:oak_planks", value);
        }
        return canonical;
    }

    private static IllegalArgumentException invalid(NodeType nodeType, NodeParameter parameter,
                                                    String expected, String actual) {
        return new IllegalArgumentException(nodeType + "." + parameter.getId() + " must be " + expected
            + "; received '" + actual + "'.");
    }

    record CheckedValue(String parameterId, String parameterName, ParameterType type, String value) { }
}

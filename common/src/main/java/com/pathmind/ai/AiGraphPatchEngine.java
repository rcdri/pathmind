package com.pathmind.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** Applies a deliberately small JSON-Pointer patch vocabulary to an in-memory graph draft. */
public final class AiGraphPatchEngine {
    private static final int MAX_OPERATIONS = 24;
    private static final int MAX_VALUE_LENGTH = 24_000;

    private AiGraphPatchEngine() {
    }

    public static Result apply(JsonObject source, JsonArray operations) {
        JsonObject draft = source.deepCopy();
        if (operations == null || operations.isEmpty()) return Result.failure("No patch operations were supplied.");
        if (operations.size() > MAX_OPERATIONS) return Result.failure("A patch may contain at most " + MAX_OPERATIONS + " operations.");
        try {
            for (JsonElement element : operations) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("Every patch operation must be an object.");
                applyOne(draft, element.getAsJsonObject());
            }
            return new Result(true, draft, "Applied " + operations.size() + " graph operation(s).");
        } catch (RuntimeException exception) {
            return Result.failure(exception.getMessage() == null ? "The graph patch was invalid." : exception.getMessage());
        }
    }

    private static void applyOne(JsonObject root, JsonObject operation) {
        String op = requiredString(operation, "op");
        String path = requiredString(operation, "path");
        if (!path.startsWith("/nodes") && !path.startsWith("/connections")
            && !path.startsWith("/routines") && !path.startsWith("/customNodeDefinition")) {
            throw new IllegalArgumentException("Patch path is outside the graph: " + path);
        }
        List<String> tokens = pointerTokens(path);
        if (tokens.isEmpty()) throw new IllegalArgumentException("Replacing the graph root is not allowed.");
        JsonElement parent = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            parent = child(parent, tokens.get(index));
        }
        String leaf = tokens.get(tokens.size() - 1);
        JsonElement value = JsonNull.INSTANCE;
        if (!"remove".equals(op)) {
            String valueJson = requiredString(operation, "valueJson");
            if (valueJson.length() > MAX_VALUE_LENGTH) throw new IllegalArgumentException("Patch value is too large.");
            value = JsonParser.parseString(valueJson);
        }
        mutate(parent, leaf, op, value);
    }

    private static void mutate(JsonElement parent, String leaf, String op, JsonElement value) {
        if (parent.isJsonObject()) {
            JsonObject object = parent.getAsJsonObject();
            if ("remove".equals(op)) {
                if (!object.has(leaf)) throw new IllegalArgumentException("Patch path does not exist: " + leaf);
                object.remove(leaf);
            } else if ("add".equals(op)) {
                if (object.has(leaf)) throw new IllegalArgumentException("Add path already exists: " + leaf);
                object.add(leaf, value);
            } else if ("replace".equals(op)) {
                if (!object.has(leaf)) throw new IllegalArgumentException("Replace path does not exist: " + leaf);
                object.add(leaf, value);
            } else {
                throw new IllegalArgumentException("Unsupported patch operation: " + op);
            }
            return;
        }
        if (!parent.isJsonArray()) throw new IllegalArgumentException("Patch parent is not a container.");
        JsonArray array = parent.getAsJsonArray();
        if ("add".equals(op) && "-".equals(leaf)) {
            array.add(value);
            return;
        }
        int index = arrayIndex(leaf, array.size(), "add".equals(op));
        if ("remove".equals(op)) array.remove(index);
        else if ("replace".equals(op)) array.set(index, value);
        else if ("add".equals(op) && index == array.size()) array.add(value);
        else if ("add".equals(op)) throw new IllegalArgumentException("Array additions must append with '/-'.");
        else throw new IllegalArgumentException("Unsupported patch operation: " + op);
    }

    private static JsonElement child(JsonElement parent, String token) {
        if (parent.isJsonObject()) {
            JsonElement child = parent.getAsJsonObject().get(token);
            if (child == null) throw new IllegalArgumentException("Patch path does not exist: " + token);
            return child;
        }
        if (parent.isJsonArray()) return parent.getAsJsonArray().get(arrayIndex(token, parent.getAsJsonArray().size(), false));
        throw new IllegalArgumentException("Patch path crosses a scalar value.");
    }

    private static int arrayIndex(String token, int size, boolean allowEnd) {
        try {
            int index = Integer.parseInt(token);
            int maximum = allowEnd ? size : size - 1;
            if (index < 0 || index > maximum) throw new IllegalArgumentException("Array index is out of bounds: " + token);
            return index;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid array index: " + token);
        }
    }

    private static List<String> pointerTokens(String path) {
        String[] raw = path.substring(1).split("/", -1);
        List<String> result = new ArrayList<>(raw.length);
        for (String token : raw) result.add(token.replace("~1", "/").replace("~0", "~"));
        return result;
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw new IllegalArgumentException("Patch operation is missing '" + key + "'.");
        return object.get(key).getAsString();
    }

    public record Result(boolean success, JsonObject graph, String message) {
        static Result failure(String message) { return new Result(false, null, message); }
    }
}

package com.pathmind.nodes;

/** Domain meaning beyond the persisted primitive type; shared by documentation and validation. */
public record ParameterValueContract(String format, Double minimum, String unit, String description) {
    public static ParameterValueContract identifier(String registry) {
        return new ParameterValueContract("resource_identifier", null, "", "One " + registry
            + " identifier, not a list or an identifier followed by a quantity.");
    }

    public static ParameterValueContract quantity(double minimum, String unit) {
        return new ParameterValueContract("number", minimum, unit, "Quantity measured in " + unit + ".");
    }

    public String validate(ParameterType type, String raw) {
        String value = raw == null ? "" : raw;
        String trimmed = value.trim();
        if ("resource_identifier".equals(format)) {
            String canonical = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
            if (!canonical.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
                throw new IllegalArgumentException("Expected one resource identifier; received '" + value + "'.");
            }
            return canonical;
        }
        switch (type) {
            case INTEGER -> {
                if (!trimmed.matches("-?[0-9]+")) throw new IllegalArgumentException("Expected a whole number.");
                int parsed;
                try { parsed = Integer.parseInt(trimmed); }
                catch (NumberFormatException failure) { throw new IllegalArgumentException("Integer is out of range."); }
                checkMinimum(parsed);
                return Integer.toString(parsed);
            }
            case DOUBLE -> {
                double parsed;
                try { parsed = Double.parseDouble(trimmed); }
                catch (NumberFormatException failure) { throw new IllegalArgumentException("Expected a number."); }
                if (!Double.isFinite(parsed)) throw new IllegalArgumentException("Expected a finite number.");
                checkMinimum(parsed);
                return trimmed;
            }
            case BOOLEAN -> {
                if (!"true".equals(trimmed) && !"false".equals(trimmed)) {
                    throw new IllegalArgumentException("Expected true or false.");
                }
                return trimmed;
            }
            default -> { return value; }
        }
    }

    private void checkMinimum(double value) {
        if (minimum != null && value < minimum) throw new IllegalArgumentException("Value must be at least " + minimum + ".");
    }

    public static ParameterValueContract primitive() {
        return new ParameterValueContract("primitive", null, "", "");
    }
}

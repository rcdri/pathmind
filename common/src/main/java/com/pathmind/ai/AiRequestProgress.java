package com.pathmind.ai;

public record AiRequestProgress(Stage stage, String message, int toolTurn, AiToolTrace completedTool) {
    public enum Stage { THINKING, INSPECTING, PLANNING, BUILDING, VALIDATING, REPAIRING, AWAITING_REVIEW, ANSWERED, CLARIFICATION, BLOCKED, CANCELLED, FAILED }
}

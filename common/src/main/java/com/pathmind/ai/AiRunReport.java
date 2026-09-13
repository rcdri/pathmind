package com.pathmind.ai;

/** Failed evaluations retain metrics as well as a bounded error, rather than disappearing from reports. */
public record AiRunReport(AiPresetService.Proposal proposal, AiRunMetrics metrics, String error, java.util.List<AiToolTrace> trace) {
    public AiRunReport { trace = java.util.List.copyOf(trace); }
    public AiRunReport(AiPresetService.Proposal proposal, AiRunMetrics metrics, String error) { this(proposal, metrics, error, java.util.List.of()); }
    public boolean succeeded() { return proposal != null && error == null; }
}

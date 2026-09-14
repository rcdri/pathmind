package com.pathmind.ai;

import com.pathmind.data.NodeGraphData;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Applying and saving must not report an unchanged editor after a failed save. */
public final class AiApplyTransaction {
    public enum Outcome { SAVED, REVERTED, ROLLBACK_FAILED }
    private AiApplyTransaction() { }
    public static Outcome apply(NodeGraphData previous, NodeGraphData proposed, Predicate<NodeGraphData> apply, BooleanSupplier save) {
        try { if (apply.test(proposed) && save.getAsBoolean()) return Outcome.SAVED; }
        catch (RuntimeException ignored) { }
        try { return apply.test(previous) ? Outcome.REVERTED : Outcome.ROLLBACK_FAILED; }
        catch (RuntimeException ignored) { return Outcome.ROLLBACK_FAILED; }
    }
}

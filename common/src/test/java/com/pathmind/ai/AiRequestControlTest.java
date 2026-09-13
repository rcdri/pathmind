package com.pathmind.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestControlTest {
    @Test void inspectionProgressContainsActualToolResultAndTerminalAnswer() {
        var actions = new java.util.ArrayDeque<String>(List.of(
            "{\"tool\":\"inspect_preset\",\"target\":\"inspect\",\"requestIntent\":\"diagnose\",\"intentEvidence\":\"What is wrong?\"}",
            "{\"tool\":\"finish\",\"target\":\"inspect\",\"response\":\"There is no open preset.\\n\\nOpen one to inspect it.\"}"));
        AiProvider provider = request -> CompletableFuture.completedFuture(actions.removeFirst());
        List<AiRequestProgress> progress = new ArrayList<>();
        var report = AiPresetAgent.runMeasured(provider, "test", "What is wrong?", "", null, "", true, true,
            new AiRequestControl(progress::add)).join();
        assertTrue(report.succeeded());
        assertTrue(progress.stream().anyMatch(p -> p.stage() == AiRequestProgress.Stage.INSPECTING));
        assertTrue(progress.stream().anyMatch(p -> p.completedTool() != null && p.completedTool().tool().equals("inspect_preset")));
        assertEquals(AiRequestProgress.Stage.ANSWERED, progress.getLast().stage());
        assertTrue(report.proposal().response().contains("\n\n"));
    }

    @Test void cancellationCompletesPendingRunAndIgnoresLateResponse() throws Exception {
        var pending = new CompletableFuture<AiModelTurn>();
        int[] calls = {0}; boolean[] closed = {false};
        AiProvider provider = new AiProvider() {
            public CompletableFuture<String> generate(AiPresetRequest request) { throw new AssertionError(); }
            public AiProviderSession openSession() {
                return new AiProviderSession() {
                    public CompletableFuture<AiModelTurn> generate(AiPresetRequest request, com.google.gson.JsonObject result) {
                        calls[0]++; return pending;
                    }
                    public void close() { closed[0] = true; }
                };
            }
        };
        List<AiRequestProgress> progress = new ArrayList<>();
        var control = new AiRequestControl(progress::add);
        var run = AiPresetAgent.runMeasured(provider, "test", "What do you think?", "", null, "", true, true, control);
        assertFalse(run.isDone());
        control.cancel(); control.cancel();
        var report = run.get(1, TimeUnit.SECONDS);
        assertFalse(report.succeeded()); assertNull(report.proposal());
        assertTrue(report.error().contains("cancelled")); assertTrue(closed[0]);
        pending.complete(new AiModelTurn("{}", AiTokenUsage.UNKNOWN));
        assertEquals(1, calls[0]); assertTrue(report.trace().isEmpty());
        assertEquals(AiRequestProgress.Stage.THINKING, progress.getFirst().stage());
        assertEquals(AiRequestProgress.Stage.CANCELLED, progress.getLast().stage());
    }

    @Test void cancellationBeforeStartDoesNotContactProvider() {
        var control = new AiRequestControl(); control.cancel();
        AiProvider provider = request -> { throw new AssertionError("Cancelled request contacted provider"); };
        var report = AiPresetAgent.runMeasured(provider, "test", "Hello", "", null, "", true, true, control).join();
        assertFalse(report.succeeded()); assertEquals(0, report.metrics().toolTurns());
    }

    @Test void observerFailureCannotRetryWorkAndCancellationSuppressesLateProgress() {
        int[] observed = {0};
        var control = new AiRequestControl(progress -> { observed[0]++; throw new IllegalStateException("UI gone"); });
        control.publish(new AiRequestProgress(AiRequestProgress.Stage.INSPECTING, "Inspecting", 1, null));
        control.cancel();
        control.publish(new AiRequestProgress(AiRequestProgress.Stage.BUILDING, "Late", 2, null));
        assertEquals(1, observed[0]);
    }
}

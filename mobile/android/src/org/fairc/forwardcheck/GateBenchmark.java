package org.fairc.forwardcheck;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Debug-only, synthetic-input benchmark for the on-device forwarding gate.
 *
 * <p>Run after a force-stop for a meaningful process-first-call measurement:
 * {@code adb shell am instrument -w org.fairc.forwardcheck/.GateBenchmark}</p>
 */
public final class GateBenchmark extends Instrumentation {
    private static final int WARM_REPETITIONS = 5; // 8 cases × 5 = 40 calls.

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Context context = getTargetContext();
            Runtime runtime = Runtime.getRuntime();
            long heapBefore = usedHeap(runtime);
            long pssBeforeKb = Debug.getPss();

            Case[] cases = cases();
            long coldStarted = SystemClock.elapsedRealtimeNanos();
            LocalClassifier.Decision cold = LocalClassifier.classify(context, cases[0].text);
            long coldNanos = SystemClock.elapsedRealtimeNanos() - coldStarted;

            List<Long> warmNanos = new ArrayList<>(cases.length * WARM_REPETITIONS);
            LocalClassifier.Decision[] latest = new LocalClassifier.Decision[cases.length];
            long[] caseNanos = new long[cases.length];
            for (int repetition = 0; repetition < WARM_REPETITIONS; repetition++) {
                for (int index = 0; index < cases.length; index++) {
                    long started = SystemClock.elapsedRealtimeNanos();
                    latest[index] = LocalClassifier.classify(context, cases[index].text);
                    long elapsed = SystemClock.elapsedRealtimeNanos() - started;
                    warmNanos.add(elapsed);
                    caseNanos[index] = elapsed;
                }
            }

            long heapAfter = usedHeap(runtime);
            long pssAfterKb = Debug.getPss();
            Collections.sort(warmNanos);
            JSONArray caseResults = new JSONArray();
            for (int index = 0; index < cases.length; index++) {
                LocalClassifier.Decision decision = latest[index];
                JSONObject row = new JSONObject();
                row.put("case", cases[index].name);
                row.put("code_points", cases[index].text.codePointCount(0, cases[index].text.length()));
                row.put("action", decision.action);
                row.put("label", decision.label);
                row.put("score", decision.score);
                row.put("last_warm_us", nanosToMicros(caseNanos[index]));
                caseResults.put(row);
            }

            output.putString("status", "PASS");
            output.putString("measurement_scope", "synthetic local classifier; no networking");
            output.putString("cold_case", cases[0].name);
            output.putString("cold_action", cold.action);
            output.putDouble("cold_score", cold.score);
            output.putDouble("cold_ms", nanosToMillis(coldNanos));
            output.putInt("warm_calls", warmNanos.size());
            output.putDouble("warm_median_ms", nanosToMillis(percentile(warmNanos, .50)));
            output.putDouble("warm_p95_ms", nanosToMillis(percentile(warmNanos, .95)));
            output.putLong("heap_before_bytes", heapBefore);
            output.putLong("heap_after_bytes", heapAfter);
            output.putLong("heap_delta_bytes", heapAfter - heapBefore);
            output.putLong("process_pss_before_kb", pssBeforeKb);
            output.putLong("process_pss_after_kb", pssAfterKb);
            output.putLong("process_pss_delta_kb", pssAfterKb - pssBeforeKb);
            output.putString("case_results_json", caseResults.toString());
            finish(Activity.RESULT_OK, output);
        } catch (Throwable failure) {
            // Return the error class only. No message, stack, or input text is logged.
            output.putString("status", "FAIL");
            output.putString("error_class", failure.getClass().getName());
            finish(Activity.RESULT_CANCELED, output);
        }
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(fraction * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double nanosToMillis(long nanos) { return nanos / 1_000_000.0; }
    private static double nanosToMicros(long nanos) { return nanos / 1_000.0; }

    private static Case[] cases() {
        StringBuilder stress = new StringBuilder(5000);
        String unit = "Government report says 42 districts received relief in 2026. ";
        while (stress.length() < 5000) stress.append(unit);
        stress.setLength(5000);
        return new Case[]{
                new Case("english_fact", "A government report says inflation reached 6.2 percent in 2026."),
                new Case("english_greeting", "Hello, good morning!"),
                new Case("english_opinion", "I think this movie is wonderful."),
                new Case("english_link", "Read the announcement at https://example.org/report"),
                new Case("hindi_fact", "सरकार ने 2026 में नई योजना की घोषणा की है।"),
                new Case("hindi_greeting", "नमस्ते, धन्यवाद।"),
                new Case("hindi_opinion", "मुझे लगता है यह फिल्म बहुत अच्छी है।"),
                new Case("stress_5000_chars", stress.toString()),
        };
    }

    private static final class Case {
        final String name;
        final String text;
        Case(String name, String text) { this.name = name; this.text = text; }
    }
}

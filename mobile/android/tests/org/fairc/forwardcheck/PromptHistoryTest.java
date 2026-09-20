package org.fairc.forwardcheck;

/** Behavioral regression for a reviewed claim followed by a greeting/redraw. */
public final class PromptHistoryTest {
    public static void main(String[] args) {
        PromptHistory history = new PromptHistory(512);
        check(history.shouldOffer("claim-A", true), "A new claim should prompt");
        history.acknowledge("claim-A"); // Both Not now and Review use this action.
        check(!history.shouldOffer("claim-A", true), "Returning from review must stay quiet");
        check(!history.shouldOffer("happy-diwali", false), "A greeting must not prompt");
        for (int redraw = 0; redraw < 10; redraw++) {
            check(!history.shouldOffer("claim-A", true), "New UI node identities must not revive an acknowledged claim");
        }
        check(history.shouldOffer("claim-B", true), "A different new claim must still prompt");
        history.acknowledge("claim-B");
        PromptHistory restarted = new PromptHistory(512);
        restarted.restore(history.snapshot());
        check(!restarted.shouldOffer("claim-A", true), "Restart must preserve explicit choices");
        check(!restarted.shouldOffer("claim-B", true), "Both reviewed and dismissed texts stay quiet");
        restarted.clear();
        check(restarted.shouldOffer("claim-A", true), "Explicit demo reset must allow a rehearsal");
        PromptHistory bounded = new PromptHistory(2);
        bounded.acknowledge("a"); bounded.acknowledge("b"); bounded.acknowledge("c");
        check(bounded.size() == 2, "Choice memory must remain bounded");
        System.out.println("PASS PromptHistoryTest");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

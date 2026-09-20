package org.fairc.forwardcheck;

import android.content.Context;

/** SOCIAL SCREENING: read-only bridge to the existing shared on-device model.
 * No new model, training, cloud request or WhatsApp behavior change.
 */
public final class SocialLocalGate {
    private SocialLocalGate() {}
    public static boolean shouldSkip(Context context,String text)throws Exception {
        if(text==null||text.trim().isEmpty())return true;
        // Reuse the same synchronized model/session and long-message safeguard.
        // Only a confident personal/opinion result suppresses the post locally.
        LocalClassifier.Decision decision=ContextGate.get(context).classify(text);
        return "personal_skip".equals(decision.action);
    }
}

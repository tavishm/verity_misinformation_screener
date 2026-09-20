package org.fairc.forwardcheck;

/** SMS is deliberately narrower than WhatsApp: unknown incoming scams only. */
final class SmsPolicy {
    static final String GOOGLE_MESSAGES="com.google.android.apps.messaging";
    enum ContactStatus { UNRESOLVED, SAVED, UNKNOWN }
    static boolean supported(String app) { return GOOGLE_MESSAGES.equals(app); }
    static boolean eligible(boolean enabled,boolean incoming,boolean group,ContactStatus sender) {
        return enabled && incoming && !group && sender==ContactStatus.UNKNOWN;
    }
    static boolean warn(String localAction) { return "spam_warning".equals(localAction); }
}

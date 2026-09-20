package org.fairc.forwardcheck;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

/** Separate opt-in; no SMS database permission or notification listener. */
final class SmsPrefs {
    static boolean enabled(Context context) { return context.getSharedPreferences("sms_screening",0).getBoolean("enabled",false); }
    static void enable(Context context,boolean value) { context.getSharedPreferences("sms_screening",0).edit().putBoolean("enabled",value).apply(); }
    static boolean contactsAllowed(Context context) { return context.checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED; }
}

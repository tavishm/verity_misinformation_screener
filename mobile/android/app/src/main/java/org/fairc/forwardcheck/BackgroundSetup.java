package org.fairc.forwardcheck;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/** Per-app, user-controlled exemption for the visible-chat helper's core function. */
final class BackgroundSetup {
    static boolean allowed(Context context) {
        PowerManager manager=context.getSystemService(PowerManager.class);
        return manager!=null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
    }
    static void request(Activity activity) {
        if(allowed(activity)) {
            Toast.makeText(activity,I18n.text(activity,"Background checks are allowed."),Toast.LENGTH_SHORT).show();
            return;
        }
        I18n.dialog(activity).setTitle("Keep checks on")
                .setMessage("Allow Verity to stay on while you read WhatsApp.")
                .setNegativeButton("Not now",null).setPositiveButton("Continue",(dialog,which)->{
                    Uri app=Uri.parse("package:"+activity.getPackageName());
                    try { activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,app)); }
                    catch(RuntimeException unavailable) {
                        try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,app));
                            Toast.makeText(activity,I18n.text(activity,"Open Battery and choose Unrestricted."),Toast.LENGTH_LONG).show();
                        } catch(RuntimeException noSettings) {
                            Toast.makeText(activity,I18n.text(activity,"Open Battery and choose Unrestricted."),Toast.LENGTH_LONG).show();
                        }
                    }
                }).show();
    }
}

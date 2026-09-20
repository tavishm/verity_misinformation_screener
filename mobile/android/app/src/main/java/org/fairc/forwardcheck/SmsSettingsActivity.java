package org.fairc.forwardcheck;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

/** One local-only SMS switch. Contacts permission excludes saved senders. */
public final class SmsSettingsActivity extends Activity {
    private static final int CONTACTS=31;
    @Override public void onCreate(Bundle state){super.onCreate(state);show();}
    @Override public void onResume(){super.onResume();show();}
    private void show(){
        Ui ui=new Ui(this);LinearLayout box=ui.column();box.addView(ui.title("SMS scam warnings"));
        boolean allowed=SmsPrefs.contactsAllowed(this),enabled=SmsPrefs.enabled(this)&&allowed;
        box.addView(ui.text(enabled?(ForwardAccessibilityService.helperConnected()?"On for unknown senders":"Turn on the message helper"):"SMS warnings are off",25));
        box.addView(ui.text("Warn me about possible scams while I read Google Messages.",20));
        box.addView(ui.text("Saved contacts are skipped. SMS stays on this phone.",18));
        Button toggle=ui.button(enabled?"Turn off":"Turn on",true);
        toggle.setOnClickListener(v->{if(enabled){SmsPrefs.enable(this,false);show();}else if(allowed){SmsPrefs.enable(this,true);show();}else allowContacts();});box.addView(toggle);
        if(enabled&&!ForwardAccessibilityService.helperConnected()){
            Button helper=ui.button("Turn on the message helper",false);helper.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));box.addView(helper);
        }
        Button done=ui.link("Done");done.setOnClickListener(v->finish());box.addView(done);setContentView(ui.screen(box));
    }
    private void allowContacts(){
        I18n.dialog(this).setTitle("Skip people you know")
                .setMessage("Allow contacts access so we can skip saved contacts. Your contacts and SMS stay on this phone.")
                .setNegativeButton("Not now",null).setPositiveButton("Continue",(d,w)->{
                    boolean asked=getPreferences(0).getBoolean("contacts_asked",false);
                    if(asked&&!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)){
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
                    }else{
                        getPreferences(0).edit().putBoolean("contacts_asked",true).apply();
                        requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},CONTACTS);
                    }
                }).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CONTACTS){SmsPrefs.enable(this,SmsPrefs.contactsAllowed(this));show();}
    }
}

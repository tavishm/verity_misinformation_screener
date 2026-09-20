package org.fairc.forwardcheck.social;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.util.*;
import org.fairc.forwardcheck.AppPrefs;

public final class SocialSettingsActivity extends Activity {
    private SocialPrefs prefs;private SocialUi ui;private Switch toggle;private TextView state;
    private final List<CheckBox> apps=new ArrayList<>();private boolean binding;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);prefs=new SocialPrefs(this);ui=new SocialUi(this);
        LinearLayout box=ui.column();box.addView(ui.title("Social media"));box.addView(ui.text("Pause on a post. See a quick check.",20));
        toggle=new Switch(this);toggle.setText("Screen posts");toggle.setTextSize(22);toggle.setPadding(0,ui.dp(20),0,ui.dp(20));box.addView(toggle);
        box.addView(ui.text("Choose your apps",21));Set<String> chosen=prefs.apps();
        for(int i=0;i<SocialPrefs.NAMES.length;i++){CheckBox c=new CheckBox(this);c.setText(SocialPrefs.NAMES[i]);c.setTextSize(21);c.setMinHeight(ui.dp(54));c.setChecked(chosen.contains(SocialPrefs.PACKAGES[i]));apps.add(c);box.addView(c);c.setOnCheckedChangeListener((v,on)->{if(!binding&&prefs.enabled()){prefs.enable(selected());SocialClient.get(this).cancelAll();refresh();}});}
        box.addView(ui.text("Posts you pause on go online for checking. We do not add your name or account. The post itself may include names or private details.",17));
        box.addView(ui.text("Social checks pause at the "+SocialBudget.limitLabel()+" monthly limit.",17));
        state=ui.text("",18);box.addView(state);
        Button helper=ui.button("Turn on social helper",true);helper.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));box.addView(helper);
        Button costs=ui.button("Checking costs",false);costs.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Social checking costs").setMessage(SocialClient.get(this).costSummary()).setPositiveButton("Done",null).show());box.addView(costs);
        Button done=ui.button("Done",false);done.setOnClickListener(v->finish());box.addView(done);
        toggle.setOnCheckedChangeListener((v,on)->{if(binding)return;if(!on){prefs.disable();SocialClient.get(this).cancelAll();refresh();return;}if(selected().isEmpty()){Toast.makeText(this,"Choose an app first.",Toast.LENGTH_SHORT).show();refresh();return;}
            new AlertDialog.Builder(this).setTitle("Check posts online?").setMessage("Posts you pause on in "+names()+" will be sent automatically to AI services. Your account details are not added.\n\nQuick AI answers can be wrong. You can turn this off at any time.")
                .setNegativeButton("Cancel",(d,w)->refresh()).setPositiveButton("Turn on",(d,w)->{prefs.enable(selected());SocialClient.get(this).warm();refresh();}).setOnCancelListener(d->refresh()).show();});
        setContentView(ui.screen(box));refresh();
    }
    @Override public void onResume(){super.onResume();if(prefs!=null)refresh();}
    private Set<String> selected(){Set<String> out=new HashSet<>();for(int i=0;i<apps.size();i++)if(apps.get(i).isChecked())out.add(SocialPrefs.PACKAGES[i]);return out;}
    private String names(){List<String> n=new ArrayList<>();for(int i=0;i<apps.size();i++)if(apps.get(i).isChecked())n.add(SocialPrefs.NAMES[i]);return String.join(", ",n);}
    private void refresh(){binding=true;toggle.setChecked(prefs.enabled());binding=false;state.setText(!prefs.enabled()?"Off":!new AppPrefs(this).paired()?"Add your checking key in the app's Settings.":!SocialScreeningService.connected()?"Turn on ‘Verity · Social’ in Accessibility.":"Ready. Open "+prefs.appNames()+".");}
}

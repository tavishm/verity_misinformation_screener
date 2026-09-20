package org.fairc.forwardcheck.social;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

public final class SocialPrefs {
    public static final String FILE="social_screening";
    // Each app remains opt-in; a new supported adapter never selects itself.
    public static final String[] PACKAGES={SocialExtractor.X,SocialExtractor.REDDIT,SocialExtractor.NEWS};
    public static final String[] NAMES={"X","Reddit","Google News"};
    private final SharedPreferences prefs;
    public SocialPrefs(Context c){prefs=c.getApplicationContext().getSharedPreferences(FILE,Context.MODE_PRIVATE);}
    public boolean enabled(){return prefs.getBoolean("enabled",false)&&prefs.getInt("consent_version",0)==1&&!apps().isEmpty();}
    public Set<String> apps(){Set<String> out=new HashSet<>(prefs.getStringSet("apps",Collections.emptySet()));out.retainAll(Arrays.asList(PACKAGES));return out;}
    public boolean allows(String app){return enabled()&&apps().contains(app);}
    public long revision(){return prefs.getLong("revision",0);}
    public void enable(Set<String> apps){Set<String> selected=new HashSet<>(apps);selected.retainAll(Arrays.asList(PACKAGES));prefs.edit().putStringSet("apps",selected).putInt("consent_version",1).putLong("revision",revision()+1).putBoolean("enabled",!selected.isEmpty()).apply();}
    public void disable(){prefs.edit().putLong("revision",revision()+1).putBoolean("enabled",false).apply();}
    public String appNames(){List<String> names=new ArrayList<>();Set<String> a=apps();for(int i=0;i<PACKAGES.length;i++)if(a.contains(PACKAGES[i]))names.add(NAMES[i]);return String.join(", ",names);}
}

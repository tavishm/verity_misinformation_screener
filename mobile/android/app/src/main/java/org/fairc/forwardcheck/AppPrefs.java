package org.fairc.forwardcheck;
import android.content.Context;
/** Only a device-bound key; no desktop address or pairing token is needed. */
public final class AppPrefs {
    final Context context;
    final KeyVault keyVault;
    public AppPrefs(Context context){this.context=context.getApplicationContext();keyVault=new KeyVault(this.context);}
    public boolean paired(){return keyVault.configured();}
    public String apiKey()throws Exception{return keyVault.read();}
    public void setApiKey(String value)throws Exception{keyVault.save(value);}
}

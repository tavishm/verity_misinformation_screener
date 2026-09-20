package org.fairc.forwardcheck;
import android.content.*;
import org.json.*;
import java.text.Normalizer;
import java.util.*;
final class ChosenContacts {
    private final android.content.SharedPreferences prefs;
    ChosenContacts(Context c) { prefs=c.getSharedPreferences("chosen_contacts",Context.MODE_PRIVATE); }
    JSONArray rows() { try { return new JSONArray(prefs.getString("rows","[]")); } catch(Exception e) { return new JSONArray(); } }
    void add(String name,String phone) throws Exception {
        name=name==null?"":name.trim();phone=phone==null?"":phone.trim();
        if(name.isEmpty() && phone.isEmpty())throw new IllegalArgumentException("No contact selected");
        JSONArray rows=rows();JSONObject row=new JSONObject().put("name",name).put("phone",phone);boolean found=false;
        for(int i=0;i<rows.length();i++){JSONObject old=rows.optJSONObject(i);if(old!=null && !phone.isEmpty() && samePhone(phone,old.optString("phone"))){rows.put(i,row);found=true;break;}}
        if(!found)rows.put(row);if(!prefs.edit().putString("rows",rows.toString()).commit())throw new java.io.IOException("Could not save contact choice");
    }
    void remove(int index) { JSONArray rows=rows(); rows.remove(index); prefs.edit().putString("rows",rows.toString()).commit(); }
    boolean matches(String header) {
        String key=normalize(header), digits=header.replaceAll("\\D",""); JSONArray rows=rows();
        for(int i=0;i<rows.length();i++) { JSONObject row=rows.optJSONObject(i); if(row==null)continue;
            if(!key.isEmpty() && key.equals(normalize(row.optString("name"))))return true;
            String phone=row.optString("phone").replaceAll("\\D","");
            if(samePhone(digits,phone))return true;
        } return false;
    }
    static boolean samePhone(String first,String second){
        String a=first.replaceAll("\\D",""),b=second.replaceAll("\\D","");
        if(a.length()<8 || b.length()<8)return false;if(a.equals(b))return true;
        // Match a local ten-digit display to the chosen international number.
        // Two different explicit country prefixes must never be conflated.
        return (a.length()==10 && b.length()>10 && b.endsWith(a)) || (b.length()==10 && a.length()>10 && a.endsWith(b));
    }
    static String normalize(String value) { return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).replaceAll("[\\p{Cf}]", "").trim().toLowerCase(Locale.ROOT); }
}

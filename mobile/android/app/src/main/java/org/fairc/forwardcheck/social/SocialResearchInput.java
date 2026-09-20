package org.fairc.forwardcheck.social;

import org.json.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Transient media/link context supplied only after an explicit deeper-research tap. */
public final class SocialResearchInput {
    static final int MAX_LINKS=3,MAX_LINK_CHARS=2048,MAX_JPEG_DATA_URL_CHARS=1_500_000;
    private static final String JPEG_PREFIX="data:image/jpeg;base64,";
    public final String text,jpegDataUrl;
    public final List<String> links;

    public SocialResearchInput(String text,String jpegDataUrl,List<String> links){
        this.text=text==null?"":text;
        this.jpegDataUrl=validJpeg(jpegDataUrl)?jpegDataUrl:"";
        ArrayList<String> safe=new ArrayList<>();
        if(links!=null)for(String link:links){String value=safeLink(link);if(!value.isEmpty()&&!safe.contains(value)){safe.add(value);if(safe.size()>=MAX_LINKS)break;}}
        this.links=Collections.unmodifiableList(safe);
    }
    public boolean hasImage(){return !jpegDataUrl.isEmpty();}
    /** Include this in detailed cache/work identity; never reuse a verdict across another crop or link set. */
    public String attachmentDigest(){
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(jpegDataUrl.getBytes(StandardCharsets.UTF_8));for(String link:links){digest.update((byte)0);digest.update(link.getBytes(StandardCharsets.UTF_8));}
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(Exception unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    String userText()throws JSONException{
        JSONArray urls=new JSONArray();for(String link:links)urls.put(link);
        return new JSONObject().put("post_text",text).put("links",urls)
                .put("link_status",links.isEmpty()?"unavailable":"provided")
                .put("image_status",hasImage()?"attached":"unavailable").toString();
    }
    private static boolean validJpeg(String value){
        if(value==null||value.length()<=JPEG_PREFIX.length()||value.length()>MAX_JPEG_DATA_URL_CHARS||!value.startsWith(JPEG_PREFIX))return false;
        for(int i=JPEG_PREFIX.length();i<value.length();i++){char c=value.charAt(i);if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='+'||c=='/'||c=='='||c=='\r'||c=='\n'))return false;}return true;
    }
    private static String safeLink(String raw){
        if(raw==null)return "";String value=raw.trim();if(value.length()<9||value.length()>MAX_LINK_CHARS)return "";
        try{URI uri=new URI(value);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)?value:"";}catch(Exception invalid){return "";}
    }
}

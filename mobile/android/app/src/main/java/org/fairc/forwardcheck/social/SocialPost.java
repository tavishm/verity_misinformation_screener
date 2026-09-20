package org.fairc.forwardcheck.social;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;

/** Immutable selected text and its on-screen anchor; never includes account metadata. */
public final class SocialPost {
    public final String app, text, key;
    public final int left, top, right, bottom;
    public final int textTop,textBottom;
    public final boolean media,completeVisibleText;
    public final java.util.List<String> links;
    public SocialPost(String app, String text, int left, int top, int right, int bottom, boolean media) {
        this(app,text,left,top,right,bottom,media,-1,-1);
    }
    public SocialPost(String app,String text,int left,int top,int right,int bottom,boolean media,int textTop,int textBottom){
        this(app,text,left,top,right,bottom,media,textTop,textBottom,java.util.Collections.emptyList());
    }
    public SocialPost(String app,String text,int left,int top,int right,int bottom,boolean media,int textTop,int textBottom,java.util.List<String> links){
        this(app,text,left,top,right,bottom,media,!media,textTop,textBottom,links);
    }
    /** Explicit completeness is for adapters that can prove a full caption even when media is present. */
    public SocialPost(String app,String text,int left,int top,int right,int bottom,boolean media,boolean completeVisibleText,int textTop,int textBottom,java.util.List<String> links){
        this.links=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(links));
        this.app=app; this.text=text.trim(); this.left=left; this.top=top; this.right=right; this.bottom=bottom; this.media=media;this.completeVisibleText=completeVisibleText;
        this.textTop=textTop;this.textBottom=textBottom;
        this.key=digest(app+"\n"+media+"\n"+completeVisibleText+"\n"+Normalizer.normalize(this.text,Normalizer.Form.NFKC).replaceAll("\\s+"," "));
    }
    static String digest(String value) {
        try { StringBuilder s=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))) s.append(String.format(Locale.ROOT,"%02x",b & 255)); return s.toString(); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}

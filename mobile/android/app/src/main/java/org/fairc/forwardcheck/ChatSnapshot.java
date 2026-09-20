package org.fairc.forwardcheck;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Copies only visible bubble contents. Contact/header data never leaves the phone. */
final class ChatSnapshot {
    static final class Bubble {
        String text="", time="", sender="", hint="", source="", signature="", instance="";
        boolean forwarded, outgoing, eligible;
        Rect image; Rect bounds; MessageTracker.Entry entry;
    }
    String chat="", title=""; boolean unknown, group, selected, sms; int windowId, nodes;
    Rect windowBounds; final List<Bubble> bubbles=new ArrayList<>();
    private String date=""; private int cues;
    static ChatSnapshot read(AccessibilityNodeInfo root, ChosenContacts contacts) {
        ChatSnapshot s=new ChatSnapshot(); s.windowId=root.getWindowId(); s.windowBounds=new Rect(); root.getBoundsInScreen(s.windowBounds);
        s.walk(root,0,false,-1); s.unknown=(s.cues & MessageExtractor.UNSAVED)!=0; s.group=(s.cues & MessageExtractor.GROUP)!=0;
        s.selected=!s.group && contacts.matches(s.title);
        if(s.title.isEmpty()) { s.bubbles.clear(); return s; }
        s.chat=hash(root.getPackageName()+"|"+ChosenContacts.normalize(s.title)+"|"+s.group);
        for(Bubble b:s.bubbles) {
            boolean incoming=!b.outgoing && b.bounds.left-s.windowBounds.left < s.windowBounds.width()/8 && s.windowBounds.right-b.bounds.right > b.bounds.left-s.windowBounds.left+s.windowBounds.width()/40;
            b.eligible=b.forwarded || (incoming && !s.group && (s.unknown || s.selected));
            b.source=b.forwarded?"forwarded":s.selected?"chosen contact":"unsaved number";
        }
        return s;
    }
    private void walk(AccessibilityNodeInfo node,int depth,boolean inside,int row) {
        if(node==null || !node.isVisibleToUser() || depth>24 || nodes++>1200)return;
        AccessibilityNodeInfo.CollectionItemInfo item=node.getCollectionItemInfo();
        if(item!=null && item.getRowIndex()>=0)row=item.getRowIndex();
        String id=leaf(node.getViewIdResourceName()); String text=value(node.getText()), desc=value(node.getContentDescription());
        cues|=MessageExtractor.senderCues(id,node.getText(),node.getContentDescription());
        if(id.equals("conversation_contact_name"))title=text;
        if(!inside && (id.equals("date") || id.equals("date_header") || id.equals("date_separator")))date=text;
        if(id.equals("main_layout") && !inside) {
            Bubble b=new Bubble(); b.bounds=new Rect(); node.getBoundsInScreen(b.bounds); b.hint=Integer.toString(node.hashCode());
            contents(node,b,0); if(!b.text.isEmpty() || b.image!=null) {
                // Two uncaptioned pictures in the same minute have identical
                // text/time signatures. Prefer the app's unique accessibility
                // ID, then its conversation row, never a recycled view hash.
                if(b.image!=null) {
                    String unique=Build.VERSION.SDK_INT>=33?value(node.getUniqueId()):"";
                    if(!unique.isEmpty())b.instance="uid:"+hash(unique);
                    else if(row>=0)b.instance="row:"+row;
                }
                b.signature=hash(b.text+"|"+b.time+"|"+b.sender+"|"+b.outgoing+"|"+(b.image!=null)); bubbles.add(b);
            }
            return;
        }
        for(int i=0;i<Math.min(100,node.getChildCount());i++) { AccessibilityNodeInfo child=node.getChild(i); if(child!=null) { walk(child,depth+1,inside,row); child.recycle(); } }
    }
    private void contents(AccessibilityNodeInfo node,Bubble b,int depth) {
        if(depth>10)return;
        boolean visible=node.isVisibleToUser();
        String id=leaf(node.getViewIdResourceName()), text=value(node.getText()), desc=value(node.getContentDescription());
        if(id.contains("quoted") || id.contains("reply_preview"))return;
        b.forwarded |= ForwardExtractor.isMarker(text) || ForwardExtractor.isMarker(desc);
        if(visible && (id.equals("message_text") || id.equals("caption"))) { if(!text.isEmpty())b.text+=(b.text.isEmpty()?"":"\n")+text; }
        if(id.equals("date") || id.equals("timestamp") || id.equals("message_time"))b.time=text;
        if(id.equals("sender_name") || id.equals("participant_name")) { b.sender=text; cues|=MessageExtractor.GROUP; }
        if(id.equals("status") || id.equals("message_status") || id.equals("msg_status") || id.contains("outgoing"))b.outgoing=true;
        // Only image message pixels, never profile pictures, stickers or link-preview thumbnails.
        if(visible && (id.equals("image") || id.equals("photo") || id.equals("image_view") || id.equals("media_content") || id.equals("image_thumb") || id.equals("photo_thumb"))) {
            Rect r=new Rect(); node.getBoundsInScreen(r); if(r.width()>100 && r.height()>100 && (b.image==null || r.width()*r.height()>b.image.width()*b.image.height())) b.image=r;
        }
        for(int i=0;i<Math.min(60,node.getChildCount());i++) { AccessibilityNodeInfo child=node.getChild(i); if(child!=null) { contents(child,b,depth+1); child.recycle(); } }
    }
    static String leaf(String id) { return id==null?"":id.substring(id.lastIndexOf('/')+1).toLowerCase(Locale.ROOT); }
    static String value(CharSequence s) { return s==null?"":s.toString().replace('\u00a0',' ').trim(); }
    static String hash(String text) { try { byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder b=new StringBuilder(); for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString(); }catch(Exception e){throw new IllegalStateException(e);} }
}

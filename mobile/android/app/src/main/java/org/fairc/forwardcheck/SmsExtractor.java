package org.fairc.forwardcheck;

import java.util.*;

/** Google Messages conversation adapter. Never falls back to reading a whole screen. */
final class SmsExtractor {
    static final int MAX_NODES=1200,MAX_DEPTH=48;
    static final class Node {
        String id="",text="",description="",hint="";
        boolean visible=true,editable,showingHint;
        int left,top,right,bottom;
        final List<Node> children=new ArrayList<>();
    }
    static final class Message {
        String text="",time="",hint="";boolean incoming,outgoing;
        int left,top,right,bottom;
    }
    static final class Frame {
        String sender="";boolean group,complete=true;
        final List<Message> messages=new ArrayList<>();
    }
    static Frame extract(String app,Node root) {
        Frame frame=new Frame();
        if(!SmsPolicy.supported(app)||root==null)return frame;
        List<Node> nodes=new ArrayList<>();flatten(root,nodes,0,frame);
        if(!frame.complete)return frame;
        Node conversation=null;
        for(Node n:nodes) {
            String id=leaf(n.id);
            if(conversation==null && oneOf(id,"conversation_2_fragment","conversation_root_container","message_list"))conversation=n;
            // A drafted reply is never a candidate, and warnings should not
            // interrupt typing. Empty compose fields/hints are harmless.
            if(n.editable&&!n.showingHint&&!clean(n.text).isEmpty())return frame;
        }
        if(conversation==null)return frame;
        header(root,frame);
        if(frame.sender.isEmpty()&&!frame.group)composeHeader(nodes,root,frame);
        if(frame.group||frame.sender.isEmpty()||frame.sender.length()>100)return frame;
        if(oneOf(frame.sender.toLowerCase(Locale.ROOT),"messages","google messages","new conversation","new message","search","संदेश","नया संदेश"))return frame;
        walk(conversation,root,frame);
        frame.messages.sort(Comparator.comparingInt(m->m.top));
        return frame;
    }
    /** Newer Messages exposes the title beside a Compose avatar without an ID.
     * Only consider that toolbar above the tagged message list, never message
     * text or the "save contact" banner. Unidentified names remain unresolved.
     */
    private static void composeHeader(List<Node> nodes,Node screen,Frame frame) {
        Node list=null;
        for(Node n:nodes)if(leaf(n.id).equals("message_list")){list=n;break;}
        if(list==null||list.top<=screen.top)return;
        boolean individualCall=false;
        for(Node n:nodes)if(n.bottom<=screen.top+(screen.bottom-screen.top)/4 &&
                oneOf(clean(n.description).toLowerCase(Locale.ROOT),"call","make a call","कॉल","कॉल करें"))individualCall=true;
        for(Node n:nodes) {
            // In the 2026 edge-to-edge layout the list extends underneath the
            // toolbar. Its explicit title-row tag supplies the boundary.
            boolean tagged=leaf(n.id).equals("top_app_bar_title_row");
            if((!tagged&&n.bottom>list.top)||n.top<screen.top||n.bottom<=n.top)continue;
            if(tagged&&n.bottom>screen.top+(screen.bottom-screen.top)/4)continue;
            List<String> titles=new ArrayList<>();int[] avatars={0};toolbarParts(n,titles,avatars);
            if((!tagged&&avatars[0]!=1)||(tagged&&avatars[0]>1)||titles.size()!=1)continue;
            String title=titles.get(0);
            // RCS can display the sender's chosen profile name, even when no
            // contact is saved. The one-person avatar + individual call action
            // establish the header; SmsSenderLookup still checks Contacts.
            boolean number=title.matches("[+()\\-\\s\\p{Z}\\p{Nd}]{3,35}");
            boolean senderId=title.matches("[A-Z]{2,3}-[A-Z0-9]{3,12}");
            if(!number&&!senderId&&(!individualCall||title.contains(",")||title.contains(" & ")||title.length()>100))continue;
            if(!frame.sender.isEmpty()&&!frame.sender.equals(title)){frame.group=true;frame.sender="";return;}
            frame.sender=title;
        }
    }
    private static void toolbarParts(Node node,List<String> titles,int[] avatars) {
        if(!node.visible||node.editable)return;
        if(leaf(node.id).equals("monogram_test_tag")){avatars[0]++;return;}
        String text=clean(node.text);if(!text.isEmpty())titles.add(text);
        for(Node child:node.children)toolbarParts(child,titles,avatars);
    }
    private static void flatten(Node n,List<Node> nodes,int depth,Frame frame) {
        if(!n.visible)return;
        if(depth>MAX_DEPTH||nodes.size()>=MAX_NODES){frame.complete=false;return;}
        nodes.add(n);for(Node child:n.children)flatten(child,nodes,depth+1,frame);
    }
    private static void header(Node n,Frame frame) {
        if(!n.visible)return;String id=leaf(n.id);
        if(oneOf(id,"message_list","message_bubble","message_data_container","incoming_message","outgoing_message","conversation_list_root_container","conversation_list"))return;
        if(oneOf(id,"group_name","group_avatar","group_participants","group_details","participant_count"))frame.group=true;
        String description=clean(n.description).toLowerCase(Locale.ROOT);
        if(oneOf(description,"group details","group info","समूह की जानकारी","ग्रुप की जानकारी"))frame.group=true;
        if(oneOf(id,"action_bar_title","conversation_title","conversation_name","toolbar_title")&&!n.editable) {
            String title=clean(n.text);if(!title.isEmpty()) {
                if(!frame.sender.isEmpty()&&!frame.sender.equals(title))frame.group=true;
                frame.sender=title;
            }
        }
        for(Node child:n.children)header(child,frame);
    }
    private static void walk(Node node,Node screen,Frame frame) {
        if(!node.visible||node.editable)return;String id=leaf(node.id);
        if(excluded(id))return;
        if(oneOf(id,"message_bubble","message_data_container","incoming_message","outgoing_message")) {
            Message message=read(node,screen);
            if(!message.text.isEmpty())frame.messages.add(message);
            return;
        }
        // Some Messages versions expose a text bubble directly in the list.
        if(oneOf(id,"message_text","message_content","incoming_message_text","outgoing_message_text")) {
            Message message=read(node,screen);
            if(!message.text.isEmpty())frame.messages.add(message);
            return;
        }
        for(Node child:node.children)walk(child,screen,frame);
    }
    private static Message read(Node bubble,Node screen) {
        Message message=new Message();message.left=bubble.left;message.top=bubble.top;message.right=bubble.right;message.bottom=bubble.bottom;message.hint=bubble.hint;
        LinkedHashSet<String> body=new LinkedHashSet<>();int[] bodyBounds={Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE};
        boolean[] direction={false,false};parts(bubble,body,bodyBounds,message,direction,0);
        message.text=String.join("\n",body).trim();
        if(message.text.length()<3||message.text.length()>5000||bubble.bottom<=screen.top||bubble.top>=screen.bottom){message.text="";return message;}
        // Explicit app metadata wins. Text supplied by the sender is never
        // interpreted as delivery status or an incoming/outgoing marker.
        if(direction[1])message.outgoing=true;
        else if(direction[0])message.incoming=true;
        else if(bodyBounds[0]!=Integer.MAX_VALUE && screen.right>screen.left){
            int width=screen.right-screen.left,left=bodyBounds[0]-screen.left,right=screen.right-bodyBounds[2];
            int separation=Math.max(24,width/18);
            message.incoming=left>=0 && left<width/4 && right-left>separation;
            message.outgoing=right>=0 && right<width/4 && left-right>separation;
        }
        // An ambiguous/full-width item remains in the sequence but cannot warn.
        return message;
    }
    private static void parts(Node n,Set<String> body,int[] bounds,Message message,boolean[] direction,int depth) {
        if(!n.visible||n.editable||depth>MAX_DEPTH)return;String id=leaf(n.id);if(excluded(id))return;
        boolean isBody=oneOf(id,"message_text","message_content","incoming_message_text","outgoing_message_text");
        if(isBody&&!clean(n.text).isEmpty()) {
            body.add(clean(n.text));bounds[0]=Math.min(bounds[0],n.left);bounds[1]=Math.min(bounds[1],n.top);bounds[2]=Math.max(bounds[2],n.right);bounds[3]=Math.max(bounds[3],n.bottom);
        }
        if(oneOf(id,"message_timestamp","timestamp"))message.time=clean(n.text);
        if(oneOf(id,"incoming_message","incoming_message_text"))direction[0]=true;
        if(oneOf(id,"outgoing_message","outgoing_message_text","message_status_sent","message_status_delivered","message_status_read"))direction[1]=true;
        if(!isBody){
            String meta=clean(n.description).toLowerCase(Locale.ROOT);
            if(oneOf(meta,"incoming","received","incoming message","received message","प्राप्त","आने वाला संदेश","प्राप्त संदेश"))direction[0]=true;
            if(oneOf(meta,"outgoing","sent","delivered","read","outgoing message","sent message","भेजा गया","भेजा गया संदेश"))direction[1]=true;
        }
        for(Node child:n.children)parts(child,body,bounds,message,direction,depth+1);
    }
    private static boolean excluded(String id){return id.contains("quoted")||id.contains("reply_preview")||id.contains("reaction")||oneOf(id,"compose_message_text","compose_view","conversation_snippet","link_preview","message_image","suggestion","message_actions");}
    static String leaf(String id){return id==null?"":id.substring(id.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);}
    private static String clean(String s){return s==null?"":s.replace('\u00a0',' ').trim();}
    private static boolean oneOf(String value,String... options){for(String option:options)if(option.equals(value))return true;return false;}
}

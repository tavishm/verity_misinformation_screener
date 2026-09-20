package org.fairc.forwardcheck;

import java.util.*;

public final class MessageExtractorTest {
    private static final class N implements ForwardExtractor.Node {
        final String id, text; final int cues, left, right; final N[] children;
        N(String id, String text, int left, int right, int cues, N... children) { this.id=id; this.text=text; this.left=left; this.right=right; this.cues=cues; this.children=children; }
        public CharSequence text(){return text;} public String viewId(){return id;} public String className(){return "android.widget.TextView";}
        public boolean visible(){return true;} public int childCount(){return children.length;} public N childAt(int i){return children[i];}
        public int senderCues(){return cues;} public int left(){return left;} public int right(){return right;}
        public String instanceId(){return Integer.toString(System.identityHashCode(this));}
    }
    static N n(String id, String text, N... children) {return new N(id,text,0,0,0,children);}
    static N bubble(int left, int right, boolean outgoing, String text) {
        N body=n("message_text",text), date=n(outgoing?"status":"date","");
        return new N("main_layout","",left,right,0,n("conversation_text_row","",body),date);
    }
    static N chat(int flags, N... bubbles) {
        N header=new N("conversation_contact_name","",0,0,flags);
        return new N("conversation_root_layout","",0,1080,0,header,n("list","",bubbles));
    }
    static void count(int count, N root) {if(MessageExtractor.extract(root).size()!=count)throw new AssertionError("Expected "+count+" candidates, got "+MessageExtractor.extract(root).size());}
    public static void main(String[] args) {
        N in=bubble(48,919,false,"The city approved a new public budget."), out=bubble(400,1032,true,"This is my own message.");
        count(1,chat(MessageExtractor.UNSAVED,in,out));
        if(!MessageExtractor.extract(chat(1,in)).get(0).source.equals("unsaved"))throw new AssertionError("source");
        count(0,chat(0,in)); // Saved contact: unforwarded text not eligible.
        count(0,chat(3,in)); // Numeric group title does not admit ordinary group messages.
        count(0,chat(1,bubble(48,919,true,"An outgoing status overrides geometry.")));
        count(0,chat(1,bubble(160,920,false,"Ambiguous centered direction is skipped.")));
        count(0,chat(1,bubble(48,919,false,"hi")));
        count(0,new N("chat_list","",0,1080,1,in));
        N forward=n("message_row","",n("meta","Forwarded"),n("message_text","A forwarded factual statement."));
        count(1,chat(0,forward)); // Existing forwarding behavior preserved.
        N duplicate=new N("main_layout","",48,919,0,n("marker","Forwarded"),n("message_text","One eligible message, two routes."));
        count(1,chat(1,duplicate));
        if(MessageExtractor.senderCues("com.whatsapp:id/conversation_contact_name","+91 98765 43210",null)!=1)throw new AssertionError("phone title");
        if(MessageExtractor.senderCues("message_text","Add to contacts",null)!=0)throw new AssertionError("body cannot impersonate banner");
        if(MessageExtractor.senderCues("conversation_contact_name","Family group",null)!=0)throw new AssertionError("named contact");
        if(MessageExtractor.senderCues("contact_banner","Not in your contacts",null)!=1)throw new AssertionError("unsaved banner");
        if(MessageExtractor.senderCues("sender_name","Name",null)!=2)throw new AssertionError("group sender");
        System.out.println("PASS MessageExtractorTest: unknown chat, incoming direction, groups, forwarded compatibility");
    }
}

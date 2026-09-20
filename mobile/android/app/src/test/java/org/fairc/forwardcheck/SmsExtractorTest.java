package org.fairc.forwardcheck;

import org.junit.Test;
import static org.junit.Assert.*;

/** Controlled accessibility fixtures; these do not replace a real-phone layout check. */
public class SmsExtractorTest {
    private SmsExtractor.Node node(String id,String text,int l,int t,int r,int b,SmsExtractor.Node... children) {
        SmsExtractor.Node n=new SmsExtractor.Node();n.id=id.contains("/")?id:"com.google.android.apps.messaging:id/"+id;
        n.text=text;n.left=l;n.top=t;n.right=r;n.bottom=b;
        for(SmsExtractor.Node child:children)n.children.add(child);
        return n;
    }
    private SmsExtractor.Node text(String body,int left,int top,int right) {
        return node("message_text",body,left,top,right,top+60);
    }
    private SmsExtractor.Node bubble(String body,boolean incoming,int top) {
        int left=incoming?24:280,right=incoming?650:1056;
        return node("message_bubble","",left,top,right,top+90,text(body,left+12,top+12,right-12));
    }
    private SmsExtractor.Node screen(String sender,SmsExtractor.Node... bubbles) {
        return node("root","",0,0,1080,2200,
                node("action_bar_title",sender,60,30,900,90),
                node("conversation_2_fragment","",0,100,1080,2100,
                        node("message_list","",0,110,1080,2000,bubbles)));
    }
    private SmsExtractor.Frame read(SmsExtractor.Node root) {
        return SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,root);
    }

    @Test public void keepsAdjacentIncomingAndOutgoingMessagesSeparate() {
        SmsExtractor.Frame f=read(screen("+91 90000 00001",
                bubble("Please send your OTP.",true,200),bubble("No, thank you.",false,400)));
        assertEquals("+91 90000 00001",f.sender);assertEquals(2,f.messages.size());
        assertEquals("Please send your OTP.",f.messages.get(0).text);assertTrue(f.messages.get(0).incoming);
        assertFalse(f.messages.get(0).outgoing);assertTrue(f.messages.get(1).outgoing);assertFalse(f.messages.get(1).incoming);
    }

    @Test public void preservesHindiTextExactly() {
        String body="अपना बैंक का पासवर्ड हमें भेजें।";
        SmsExtractor.Frame f=read(screen("VM-BANK",bubble(body,true,200)));
        assertEquals(body,f.messages.get(0).text);assertTrue(f.messages.get(0).incoming);
    }

    @Test public void acceptsComposeTagsWithoutResourcePrefix() {
        SmsExtractor.Node root=screen("+1 555 000 1000",bubble("Give me your password.",true,200));
        root.children.get(1).id="message_list";
        root.children.get(1).children.get(0).children.get(0).id="message_bubble";
        assertEquals(1,read(root).messages.size());
    }

    @Test public void explicitOutgoingMetadataWinsOverLeftAlignedText() {
        SmsExtractor.Node b=bubble("Please send your OTP.",true,200);
        b.description="sent message";
        SmsExtractor.Message m=read(screen("12345",b)).messages.get(0);
        assertTrue(m.outgoing);assertFalse(m.incoming);
    }

    @Test public void bodyWordsNeverBecomeDirectionMetadata() {
        SmsExtractor.Node b=bubble("Sent. Give me your password.",true,200);
        b.children.get(0).description="sent";
        SmsExtractor.Message m=read(screen("12345",b)).messages.get(0);
        assertTrue(m.incoming);assertFalse(m.outgoing);
    }

    @Test public void explicitIncomingHandlesWideMessagesAndHindiMetadata() {
        SmsExtractor.Node b=node("message_bubble","",0,200,1080,290,text("अपना ओटीपी बताओ।",24,210,1056));
        b.description="प्राप्त संदेश";
        assertTrue(read(screen("12345",b)).messages.get(0).incoming);
    }

    @Test public void ambiguousFullWidthMessagesCannotWarn() {
        SmsExtractor.Node b=node("message_bubble","",0,200,1080,290,text("Give me your password.",24,210,1056));
        SmsExtractor.Message m=read(screen("12345",b)).messages.get(0);
        assertFalse(m.incoming);assertFalse(m.outgoing);
    }

    @Test public void conflictingDirectionMetadataIsNeverTreatedAsIncoming() {
        SmsExtractor.Node b=bubble("Please send your OTP.",true,200);b.id="incoming_message";
        b.children.add(node("message_status_delivered","",0,0,0,0));
        SmsExtractor.Message m=read(screen("12345",b)).messages.get(0);
        assertTrue(m.outgoing);assertFalse(m.incoming);
    }

    @Test public void ignoresQuotedTextPreviewAndReactions() {
        SmsExtractor.Node b=bubble("Please ignore that scam.",true,200);
        b.children.add(node("quoted_message","",24,200,650,260,text("Send your OTP",30,200,640)));
        b.children.add(node("link_preview","",24,200,650,260,text("Give me your password",30,200,640)));
        b.children.add(node("reaction","",24,200,650,260,text("Send money",30,200,640)));
        assertEquals("Please ignore that scam.",read(screen("12345",b)).messages.get(0).text);
    }

    @Test public void composingADraftSuppressesWarnings() {
        SmsExtractor.Node root=screen("12345",bubble("Give me your password.",true,200));
        SmsExtractor.Node draft=node("compose_message_text","I'm typing",0,2000,1080,2100);draft.editable=true;
        root.children.add(draft);assertTrue(read(root).messages.isEmpty());
        draft.text="";assertEquals(1,read(root).messages.size());
        draft.text="Text message";draft.showingHint=true;assertEquals(1,read(root).messages.size());
    }

    @Test public void ignoresInboxSnippetsAndSearchResults() {
        SmsExtractor.Node inbox=node("conversation_list_root_container","",0,0,1080,2200,
                node("conversation_name","12345",0,0,800,100),
                node("conversation_snippet","Give me your password.",24,200,650,260));
        assertTrue(read(inbox).messages.isEmpty());
        assertTrue(read(screen("Search",bubble("Give me your password.",true,200))).messages.isEmpty());
    }

    @Test public void skipsGroupsAndAmbiguousHeaders() {
        SmsExtractor.Node root=screen("Friends",bubble("Give me your password.",true,200));
        root.children.add(node("group_avatar","",0,0,50,50));
        assertTrue(read(root).group);assertTrue(read(root).messages.isEmpty());
        root=screen("Alice",bubble("Give me your password.",true,200));
        root.children.add(node("conversation_name","Bob",0,0,300,50));
        assertTrue(read(root).messages.isEmpty());
    }

    @Test public void ignoresInvisibleAndOffscreenMessages() {
        SmsExtractor.Node invisible=bubble("Give me your password.",true,200);invisible.visible=false;
        assertTrue(read(screen("12345",invisible,bubble("Give me your OTP.",true,2300))).messages.isEmpty());
    }

    @Test public void neverGuessesTextFromUnknownLayoutOrApp() {
        SmsExtractor.Node root=screen("12345",node("unknown_bubble","Give me your password.",24,200,650,260));
        assertTrue(read(root).messages.isEmpty());
        assertTrue(SmsExtractor.extract("com.samsung.android.messaging",screen("12345",bubble("Give me your OTP.",true,200))).messages.isEmpty());
    }

    @Test public void malformedOrMissingHeaderProducesNoCandidates() {
        assertTrue(read(screen("",bubble("Give me your password.",true,200))).messages.isEmpty());
        assertTrue(read(null).messages.isEmpty());
        assertTrue(read(screen("Messages",bubble("Give me your password.",true,200))).messages.isEmpty());
    }

    @Test public void enforcesTreeBudgetInsteadOfPartiallyReadingMessages() {
        SmsExtractor.Node root=screen("12345",bubble("Give me your password.",true,200));
        for(int i=0;i<SmsExtractor.MAX_NODES;i++)root.children.add(node("decoration","",0,0,0,0));
        SmsExtractor.Frame f=read(root);assertFalse(f.complete);assertTrue(f.messages.isEmpty());
    }

    @Test public void enforcesDepthBudget() {
        SmsExtractor.Node root=screen("12345",bubble("Give me your password.",true,200)),parent=root;
        for(int i=0;i<SmsExtractor.MAX_DEPTH+1;i++){
            SmsExtractor.Node child=node("wrapper","",0,0,1,1);parent.children.add(child);parent=child;
        }
        assertFalse(read(root).complete);assertTrue(read(root).messages.isEmpty());
    }

    @Test public void directTypedTextNodesKeepTheirDirection() {
        SmsExtractor.Node b=node("incoming_message_text","Give me your password.",24,200,650,260);
        assertTrue(read(screen("12345",b)).messages.get(0).incoming);
    }

    @Test public void timestampIsMetadataAndNotPartOfTheClaim() {
        SmsExtractor.Node b=bubble("Give me your password.",true,200);
        b.children.add(node("message_timestamp","10:30 AM",24,260,500,290));
        SmsExtractor.Message m=read(screen("12345",b)).messages.get(0);
        assertEquals("10:30 AM",m.time);assertEquals("Give me your password.",m.text);
    }
}

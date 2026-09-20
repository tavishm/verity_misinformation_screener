package org.fairc.forwardcheck;

import java.io.InputStream;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Google Messages trees from synthetic SMS on an empty Android VM. */
public class SmsCapturedLayoutTest {
    private SmsExtractor.Node fixture(String name) throws Exception {
        try(InputStream input=getClass().getResourceAsStream("/sms/"+name+".xml")) {
            assertNotNull(input);
            Element root=(Element)DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(input).getDocumentElement().getElementsByTagName("node").item(0);
            return read(root);
        }
    }
    private SmsExtractor.Node read(Element element) {
        SmsExtractor.Node n=new SmsExtractor.Node();
        n.id=element.getAttribute("resource-id");n.text=element.getAttribute("text");n.description=element.getAttribute("content-desc");
        n.editable=element.getAttribute("class").equals("android.widget.EditText");
        n.showingHint=n.editable&&n.text.equals("Text message");
        Matcher bounds=Pattern.compile("-?\\d+").matcher(element.getAttribute("bounds"));int[] r=new int[4];
        for(int i=0;i<4&&bounds.find();i++)r[i]=Integer.parseInt(bounds.group());
        n.left=r[0];n.top=r[1];n.right=r[2];n.bottom=r[3];
        for(org.w3c.dom.Node child=element.getFirstChild();child!=null;child=child.getNextSibling())
            if(child instanceof Element)n.children.add(read((Element)child));
        return n;
    }
    @Test public void extractsIncomingSmsFromActualViewsLayout() throws Exception {
        SmsExtractor.Frame f=SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,fixture("google-messages-views"));
        assertEquals("(555) 010-2001",f.sender);assertEquals(1,f.messages.size());
        assertEquals("Please share your UPI PIN with our support team.",f.messages.get(0).text);
        assertTrue(f.messages.get(0).incoming);
    }
    @Test public void extractsComposeToolbarAndEachIncomingMessage() throws Exception {
        SmsExtractor.Frame f=SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,fixture("google-messages-compose"));
        assertEquals("(555) 010-2001",f.sender);
        assertEquals(4,f.messages.stream().filter(m->m.incoming).count());
        assertEquals(2,f.messages.stream().filter(m->m.incoming&&m.text.equals("Apna OTP hame bhejo.")).count());
        assertTrue(f.messages.stream().noneMatch(m->m.incoming&&m.text.startsWith("Texting with")));
    }
    @Test public void untaggedGroupNameAndBannerCannotBecomeSender() throws Exception {
        SmsExtractor.Node root=fixture("google-messages-compose");
        replaceHeader(root,"Family group");
        removeCall(root);
        SmsExtractor.Frame f=SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,root);
        assertTrue(f.sender.isEmpty());assertTrue(f.messages.isEmpty());
    }
    @Test public void phoneVersionAllowsListUnderTheTaggedToolbar() throws Exception {
        SmsExtractor.Frame f=SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,fixture("google-messages-2026"));
        assertEquals("(555) 010-2001",f.sender);
        assertEquals(4,f.messages.stream().filter(m->m.incoming).count());
    }
    @Test public void unsavedRcsProfileNameReachesContactLookup() throws Exception {
        SmsExtractor.Node root=fixture("google-messages-2026");replaceHeader(root,"Riya Example");
        SmsExtractor.Frame f=SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,root);
        assertEquals("Riya Example",f.sender);assertEquals(4,f.messages.stream().filter(m->m.incoming).count());
    }
    @Test public void rcsProfilePhotoDoesNotRequireMonogramTag() throws Exception {
        SmsExtractor.Node root=fixture("google-messages-2026");replaceHeader(root,"Riya Example");removeMonogram(root);
        assertEquals("Riya Example",SmsExtractor.extract(SmsPolicy.GOOGLE_MESSAGES,root).sender);
    }
    private void removeMonogram(SmsExtractor.Node n) {
        if(n.id.equals("monogram_test_tag"))n.id="profile_photo";
        for(SmsExtractor.Node c:n.children)removeMonogram(c);
    }
    private void removeCall(SmsExtractor.Node n) {
        if(n.description.equals("Call"))n.description="";
        for(SmsExtractor.Node c:n.children)removeCall(c);
    }
    private void replaceHeader(SmsExtractor.Node n,String value) {
        if(n.top<132&&n.text.equals("(555) 010-2001"))n.text=value;
        for(SmsExtractor.Node c:n.children)replaceHeader(c,value);
    }
}

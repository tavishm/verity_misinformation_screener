package org.fairc.forwardcheck.social;

import org.junit.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

public class SocialXTest {
    static class N implements SocialExtractor.Node {
        String id,text="",desc="";boolean edit;int l,t,r,b;List<N> children=new ArrayList<>();List<String> links=new ArrayList<>();
        N(String id,int top,int bottom,N...children){this.id=id;this.t=top;this.b=bottom;this.l=0;this.r=1080;this.children.addAll(Arrays.asList(children));}
        N text(String value){text=value;return this;}N desc(String value){desc=value;return this;}N links(String...value){links.addAll(Arrays.asList(value));return this;}
        public String id(){return id;}public String text(){return text;}public String description(){return desc;}public boolean visible(){return true;}public boolean editable(){return edit;}
        public int left(){return l;}public int top(){return t;}public int right(){return r;}public int bottom(){return b;}public List<N> children(){return children;}public List<String> urls(){return links;}
    }

    private static N leaf(String text,int top,int bottom){return new N("",top,bottom).text(text);}
    private static N action(String description,int top,int bottom){return new N("",top,bottom).desc(description);}
    private static N card(String body,int top,int bottom,N...extra){
        N header=new N("timeline_post",top+20,top+70,leaf("Author",top+20,top+70),leaf("@author",top+20,top+70),leaf("· 1h",top+20,top+70));
        N c=new N("",top,bottom,new N("",top,top+80).desc("Author"),header,action("Post options",top,top+100),leaf(body,top+75,top+135),
                new N("",top+135,bottom,action("Reply",top+150,top+190),leaf("12",top+150,top+190)),
                new N("",top+135,bottom,action("Repost",top+150,top+190),leaf("34",top+150,top+190)),
                new N("",top+135,bottom,action("Like",top+150,top+190),leaf("56",top+150,top+190)),
                new N("",top+135,bottom,action("Share",top+150,top+190)));
        c.children.addAll(Arrays.asList(extra));return c;
    }
    private static N home(N...cards){N feed=new N("",0,2140,cards);N scaffold=new N("scaffold_home_tabbed",0,2340,feed);return new N("root",0,2340,new N("MainLanding",0,2340,scaffold));}

    @Test public void currentCapturedNativeHomeKeepsOnlyCompleteVisibleCard()throws Exception{
        InputStream in=getClass().getResourceAsStream("/social/x-native-feed.xml");assertNotNull(in);
        N root=xml(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement());
        List<SocialPost> posts=SocialX.extract(root,2340);
        assertEquals(1,posts.size());SocialPost post=posts.get(0);
        assertTrue(post.text.startsWith("Bankers keep India connected 24×7."));
        assertTrue(post.text.contains("A 5-day week was agreed in March 2024"));
        assertTrue(post.text.endsWith("The Show more"));
        assertFalse(post.text.contains("All India Bank Officers"));assertFalse(post.text.contains("42.9K"));
        assertTrue(post.media);assertFalse(post.completeVisibleText);assertEquals(487,post.textTop);assertEquals(1107,post.textBottom);assertTrue(post.bottom<=2140);
    }

    @Test public void metadataIsExcludedAndOnlyBodyHttpsLinksSurvive(){
        N c=card("The public report confirms the launch.",300,600);
        c.children.get(0).links("https://x.com/author");
        c.children.get(3).links("https://example.org/report","http://unsafe.example/report","https://user@example.org/private");
        SocialPost post=SocialX.extract(home(c),2340).get(0);
        assertEquals("The public report confirms the launch.",post.text);
        assertEquals(Collections.singletonList("https://example.org/report"),post.links);
    }

    @Test public void quotedPostKeepsCommentaryAndQuoteButRequiresResearch(){
        N quoted=new N("",470,650,new N("timeline_post",480,520,leaf("Quoted Author",480,520),leaf("@quoted",480,520)),leaf("Earth has two natural moons.",530,590));
        N c=card("This claim is incorrect:",300,700,quoted);
        SocialPost post=SocialX.extract(home(c),2340).get(0);
        assertEquals("This claim is incorrect:\nEarth has two natural moons.",post.text);
        assertTrue(post.media);assertFalse(post.completeVisibleText);assertFalse(post.text.contains("Quoted Author"));
    }

    @Test public void mediaPollAndUnknownNestedContentCannotGetTextOnlyVerdict(){
        N image=action("Image",470,650),poll=new N("",470,650,leaf("Yes",490,530),leaf("No",540,580)).desc("Poll");
        SocialPost imagePost=SocialX.extract(home(card("A photograph shows the new bridge.",300,700,image)),2340).get(0);
        SocialPost pollPost=SocialX.extract(home(card("Did the bridge open today?",300,700,poll)),2340).get(0);
        assertTrue(imagePost.media);assertTrue(imagePost.completeVisibleText);assertTrue(pollPost.media);assertFalse(pollPost.completeVisibleText);assertTrue(pollPost.text.contains("Yes"));
    }

    @Test public void adsPrivateEditorsUnknownAndClippedBodiesAreSkipped(){
        N ad=card("The product cures every illness.",300,600,leaf("Promoted",450,480));assertTrue(SocialX.extract(home(ad),2340).isEmpty());
        N editor=home(card("The Moon is made of cheese.",300,600));N edit=leaf("Write a post",50,100);edit.edit=true;editor.children.add(edit);assertTrue(SocialX.extract(editor,2340).isEmpty());
        assertTrue(SocialX.extract(new N("unknown",0,2340,card("The Moon is made of cheese.",300,600)),2340).isEmpty());
        N clipped=card("This complete sentence is below the visible edge.",2050,2300);assertTrue(SocialX.extract(home(clipped),2140).isEmpty());
        N dm=home(card("The Moon is made of cheese.",300,600));dm.children.add(new N("direct_message_root",0,100));assertTrue(SocialX.extract(dm,2340).isEmpty());
    }

    @Test public void longFullyVisibleFactBodyIsRetained(){
        String text="The committee published a detailed report stating that 120 bridges were inspected across Delhi on September 19, and that every listed inspection was completed before the evening deadline. "+"Additional documented context remains part of the same visible post.".repeat(8);
        SocialPost post=SocialX.extract(home(card(text,300,1000)),2340).get(0);
        assertEquals(text,post.text);assertFalse(post.media);assertTrue(post.completeVisibleText);assertEquals(375,post.textTop);assertEquals(435,post.textBottom);
    }

    @Test public void completeVideoCaptionDiffersFromTruncatedCaption(){
        SocialPost complete=SocialX.extract(home(card("The committee released its report today.",300,700,action("Video",470,650))),2340).get(0);
        SocialPost truncated=SocialX.extract(home(card("The committee released its report…",300,700,action("Image",470,650))),2340).get(0);
        assertTrue(complete.media);assertTrue(complete.completeVisibleText);assertTrue(truncated.media);assertFalse(truncated.completeVisibleText);
    }

    @Test public void legacyConstructorsRemainConservativeForMedia(){
        SocialPost legacy=new SocialPost(SocialExtractor.X,"A complete visible caption.",0,0,100,100,true);
        assertFalse(legacy.completeVisibleText);
        assertTrue(new SocialPost(SocialExtractor.X,"A complete visible caption.",0,0,100,100,false).completeVisibleText);
        SocialPost explicit=new SocialPost(SocialExtractor.X,"A complete visible caption.",0,0,100,100,true,true,-1,-1,Collections.emptyList());
        assertTrue(explicit.completeVisibleText);assertNotEquals(legacy.key,explicit.key);
    }

    private static N xml(Element e){
        int[] bounds=bounds(e.getAttribute("bounds"));N n=new N(e.getAttribute("resource-id"),bounds[1],bounds[3]);n.l=bounds[0];n.r=bounds[2];n.text=e.getAttribute("text");n.desc=e.getAttribute("content-desc");n.edit="true".equals(e.getAttribute("editable"));
        NodeList list=e.getChildNodes();for(int i=0;i<list.getLength();i++)if(list.item(i) instanceof Element)n.children.add(xml((Element)list.item(i)));return n;
    }
    private static int[] bounds(String value){Matcher m=Pattern.compile("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matcher(value);if(!m.matches())return new int[]{0,0,0,0};return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4))};}
}

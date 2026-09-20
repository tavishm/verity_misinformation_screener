package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class SocialExtractorTest {
    static class N implements SocialExtractor.Node {
        String id,text,desc="";boolean edit;List<N> nodes;int y,x,width=500,height=150;
        N(String id,String text,N...children){this.id=id;this.text=text;nodes=Arrays.asList(children);y=100;}
        public String id(){return id;}public String text(){return text;}public String description(){return desc;}public boolean visible(){return true;}public boolean editable(){return edit;}
        public int left(){return x;}public int top(){return y;}public int right(){return x+width;}public int bottom(){return y+height;}public List<N> children(){return nodes;}
    }
    @Test public void xGroupsQuoteAndCommentary(){N root=new N("tweet_row","",new N("username","@privateAccount"),new N("tweet_text","This allegation is false:"),new N("quoted_tweet_text","Earth has two moons."));List<SocialPost> p=SocialExtractor.extract(SocialExtractor.X,root,1000);assertEquals(1,p.size());assertEquals("This allegation is false:\nEarth has two moons.",p.get(0).text);}
    @Test public void retainsActualBodyLinksButNotAuthorProfiles(){N author=new N("username","@person"){@Override public List<String> urls(){return Collections.singletonList("https://example.org/profile");}};N body=new N("tweet_text","The report has been released."){@Override public List<String> urls(){return Collections.singletonList("https://example.org/article");}};SocialPost post=SocialExtractor.extract(SocialExtractor.X,new N("tweet_row","",author,body),1000).get(0);assertEquals(Collections.singletonList("https://example.org/article"),post.links);}
    @Test public void redditCamelCaseIds(){N root=new N("postContainer","",new N("postTitle","Earth has only one natural moon."),new N("comment_count","300 comments"));assertEquals("Earth has only one natural moon.",SocialExtractor.extract(SocialExtractor.REDDIT,root,1000).get(0).text);}
    @Test public void newsCardOnlyUploadsHeadline(){N root=new N("article_card","",new N("article_title","Earth has only one natural moon."),new N("timestamp","1 hour ago"));assertEquals(1,SocialExtractor.extract(SocialExtractor.NEWS,root,1000).size());}
    @Test public void dmAndEditorNeverUpload(){N body=new N("tweet_text","Earth has two moons.");assertTrue(SocialExtractor.extract(SocialExtractor.X,new N("direct_message_root","",body),1000).isEmpty());N edit=new N("composer","",body);edit.edit=true;assertTrue(SocialExtractor.extract(SocialExtractor.X,edit,1000).isEmpty());}
    @Test public void unknownLayoutsAndAppsSkipped(){assertTrue(SocialExtractor.extract(SocialExtractor.X,new N("random_label","Earth has two moons."),1000).isEmpty());assertTrue(SocialExtractor.extract("com.whatsapp",new N("tweet_text","Earth has two moons."),1000).isEmpty());}
    @Test public void truncatedTextCannotGetQuickTick(){SocialPost p=SocialExtractor.extract(SocialExtractor.X,new N("tweet_text","The Earth is flat, but…"),1000).get(0);assertFalse(SocialPolicy.quickEligible(p));}
    @Test public void choosesVisibleReadingTarget(){N top=new N("tweet_text","Top post is off screen.");top.y=-400;N visible=new N("tweet_text","The Earth goes around the Sun.");visible.y=400;List<SocialPost> p=SocialExtractor.extract(SocialExtractor.X,new N("root","",top,visible),1000);assertEquals(1,p.size());assertEquals(visible.text,SocialExtractor.choose(p,1000).text);}
    private static N merged(String text,N...extra){N label=new N("","");label.desc="From Science, Posted 1 day ago, "+text+", 889 upvotes, 366 comments, Reposted 0 times";List<N> children=new ArrayList<>();children.add(label);children.addAll(Arrays.asList(extra));return new N("post_unit","",children.toArray(new N[0]));}
    @Test public void nativeRedditMergedCardKeepsClaimOnly(){List<SocialPost> p=SocialExtractor.extract(SocialExtractor.REDDIT,merged("Earth has only one natural moon."),1000);assertEquals(1,p.size());assertEquals("Earth has only one natural moon.",p.get(0).text);assertTrue(SocialPolicy.quickEligible(p.get(0)));}
    @Test public void redditLinkDomainIsMetadataNotClaim(){assertEquals("The Moon is not a planet.",SocialExtractor.extract(SocialExtractor.REDDIT,merged("Link domain: example.com, The Moon is not a planet."),1000).get(0).text);}
    @Test public void nativeRedditKeepsCommasNumbersAndNegation(){String t="The Earth is not flat, and it is 150 million km from the Sun.";assertEquals(t,SocialExtractor.extract(SocialExtractor.REDDIT,merged(t),1000).get(0).text);}
    @Test public void nativeRedditPreviewNeedsCloserCheck(){SocialPost p=SocialExtractor.extract(SocialExtractor.REDDIT,merged("Earth has only one natural moon.",new N("post_preview_text","")),1000).get(0);assertFalse(SocialPolicy.quickEligible(p));}
    @Test public void nativeRedditMediaNeedsCloserCheck(){SocialPost p=SocialExtractor.extract(SocialExtractor.REDDIT,merged("Earth has only one natural moon.",new N("post_self_image","")),1000).get(0);assertTrue(p.media);}
    @Test public void redditThumbnailDoesNotMakeCompleteHeadlineUncheckable(){SocialPost p=SocialExtractor.extract(SocialExtractor.REDDIT,merged("Unfortunate death of a student at IIT Bombay",new N("post_link_preview","")),1000).get(0);assertTrue(p.media);assertTrue(p.completeVisibleText);}
    @Test public void redditBodyPreviewAndTruncationStillRequireFullText(){for(N card:Arrays.asList(merged("Earth has one moon, but…",new N("post_link_preview","")),merged("Earth has only one natural moon.",new N("post_preview_text","")),merged("Earth has only one natural moon.",new N("read_more","Read more"))))assertFalse(SocialExtractor.extract(SocialExtractor.REDDIT,card,1000).get(0).completeVisibleText);}
    @Test public void nativeRedditIgnoresAdsAndUnrecognizedEnvelope(){N ad=merged("Earth has two moons.");ad.id="promoted_post_unit";assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,ad,1000).isEmpty());N changed=merged("Earth has two moons.");changed.nodes.get(0).desc="An unknown layout with a claim.";assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,changed,1000).isEmpty());}
    @Test public void nativeRedditDoesNotParseArbitraryScreenLabels(){N n=merged("Earth has two moons.");n.id="chat_thread";assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).isEmpty());n.id="unknown_view";assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).isEmpty());}
    @Test public void nativeRedditEngagementDoesNotChangeIdentity(){N n=merged("Earth has only one natural moon.");SocialPost first=SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).get(0);n.nodes.get(0).desc=n.nodes.get(0).desc.replace("889 upvotes","900 upvotes").replace("1 day ago","2 days ago");assertEquals(first.key,SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).get(0).key);}
    @Test public void nativeRedditDeepDecorStillWorksButUnboundedTreeDoesNot(){N n=merged("Earth has only one natural moon.");for(int i=0;i<30;i++)n=new N("android_decor","",n);assertEquals(1,SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).size());for(int i=0;i<40;i++)n=new N("android_decor","",n);assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).isEmpty());}
    @Test public void nativeRedditViewCountsAreMetadata(){N n=merged("Earth has only one natural moon.");SocialPost first=SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).get(0);n.nodes.get(0).desc+=", 34.9 thousand views";SocialPost withViews=SocialExtractor.extract(SocialExtractor.REDDIT,n,1000).get(0);assertEquals(first.key,withViews.key);assertEquals(first.text,withViews.text);}
    private static N newsCard(String title){
        N coverage=new N("","");coverage.desc="View Full coverage for "+title;
        N options=new N("","");options.desc="More options for "+title;
        return new N("","",new N("","Example News Publisher"),new N("",title),new N("","7 hours ago"),new N("","",coverage,new N("","See more")),new N("","",options));
    }
    @Test public void nativeNewsReadsHeadlineWithoutMetadataOrImage(){
        N card=newsCard("Earth has only one natural moon.");
        List<SocialPost> found=SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",new N("","",card)),1000);
        assertEquals(1,found.size());assertEquals("Earth has only one natural moon.",found.get(0).text);assertFalse(found.get(0).media);assertEquals(card.top(),found.get(0).top);
    }
    @Test public void nativeNewsKeepsSeparateHeadlinesAndTheirExactWords(){
        N a=newsCard("The Moon does not orbit Mars."),b=newsCard("Earth has only one natural moon.");b.y=400;
        List<SocialPost> found=SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",new N("","",a),new N("","",b)),1000);
        assertEquals(2,found.size());assertEquals("The Moon does not orbit Mars.",found.get(0).text);assertEquals("Earth has only one natural moon.",found.get(1).text);assertEquals(400,found.get(1).top);
    }
    @Test public void nativeNewsRequiresKnownRootAndMatchingOptionsControl(){
        N card=newsCard("Earth has only one natural moon.");
        assertTrue(SocialExtractor.extract(SocialExtractor.NEWS,card,1000).isEmpty());
        assertTrue(SocialExtractor.extract(SocialExtractor.REDDIT,new N("compose_view","",card),1000).isEmpty());
        card.nodes.get(4).nodes.get(0).desc="More options for A completely different headline.";
        assertTrue(SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",card),1000).isEmpty());
    }
    @Test public void nativeNewsRejectsComposerAndProtectsTruncatedHeadline(){
        N card=newsCard("The Moon does not orbit Mars, but…");N root=new N("compose_view","",card);
        assertTrue(SocialExtractor.extract(SocialExtractor.NEWS,root,1000).get(0).media);
        N editor=new N("","Search news");editor.edit=true;root.nodes=Arrays.asList(card,editor);
        assertTrue(SocialExtractor.extract(SocialExtractor.NEWS,root,1000).isEmpty());
    }
    @Test public void groupedNewsHeadlinesDoNotNeedTheirOwnCoverageButton(){
        N first=newsCard("Earth has only one natural moon."),related=newsCard("Mars has two natural moons.");related.y=400;
        first.nodes=new ArrayList<>(first.nodes);first.nodes.remove(3);
        related.nodes=new ArrayList<>(related.nodes);related.nodes.remove(3);
        N full=new N("","Full coverage of this story");full.desc="View Full coverage for Earth has only one natural moon.";
        List<SocialPost> found=SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",new N("","",first,related,full)),1000);
        assertEquals(2,found.size());assertEquals("Earth has only one natural moon.",found.get(0).text);assertEquals("Mars has two natural moons.",found.get(1).text);
    }
    @Test public void horizontalPeekIsNotScreenedAndBottomTabsAreExcluded(){
        N first=newsCard("Earth has only one natural moon."),peek=newsCard("Mars has two natural moons.");peek.x=470;peek.width=30;
        first.y=750;first.height=250;N nav=new N("nav_bar_container","");nav.y=900;
        List<SocialPost> found=SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",first,peek,nav),1000);
        assertEquals(1,found.size());assertEquals(900,found.get(0).bottom);
    }
    @Test public void readingTargetsIncludeMultipleVisibleCardsButRemainBounded(){
        List<SocialPost> posts=new ArrayList<>();for(int y:new int[]{-500,100,300,500,700})posts.add(new SocialPost(SocialExtractor.NEWS,"Headline number "+y,0,y,500,y+150,false));
        assertEquals(4,SocialExtractor.readingTargets(posts,1000,4).size());assertEquals(2,SocialExtractor.readingTargets(posts,1000,2).size());
        for(SocialPost p:SocialExtractor.readingTargets(posts,1000,4))assertTrue(p.top>=0);
    }
    @Test public void sameNewsHeadlineInTwoCardsKeepsTwoAnchors(){N a=newsCard("Earth is round."),b=newsCard("Earth is round.");b.y=400;List<SocialPost> found=SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",a,b),1000);assertEquals(2,found.size());assertEquals(found.get(0).key,found.get(1).key);assertNotEquals(found.get(0).top,found.get(1).top);}
    @Test public void newsHeadlineBehindBottomTabsWaitsUntilVisible(){N card=newsCard("Earth is round.");card.y=750;card.height=250;card.nodes.get(1).y=850;N nav=new N("nav_bar_container","");nav.y=900;assertTrue(SocialExtractor.extract(SocialExtractor.NEWS,new N("compose_view","",card,nav),1000).isEmpty());}
}

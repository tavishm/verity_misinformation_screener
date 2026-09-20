package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
public class SocialBadgePlacementTest {
    @Test public void leadPhotoBadgeDoesNotCoverHeadline(){SocialPost p=new SocialPost(SocialExtractor.NEWS,"A news headline.",0,407,1080,1296,false,1045,1185);int y=SocialBadgePlacement.top(p,141,2340,2.8125f);assertTrue(y>=407);assertTrue(y+141<1045);}
    @Test public void relatedStoryUsesItsFreeFooter(){SocialPost p=new SocialPost(SocialExtractor.NEWS,"A related headline.",56,1330,942,1764,false,1443,1590);int y=SocialBadgePlacement.top(p,138,2340,2.8125f);assertTrue(y>1590);assertTrue(y+138<1764);}
    @Test public void nativeXCompactBadgeFitsBelowText(){SocialPost p=new SocialPost(SocialExtractor.X,"A factual text post.",0,701,1080,1006,false,792,885);int y=SocialBadgePlacement.top(p,99,2340,2.8125f);assertTrue(y>885);assertTrue(y+99<1006);}
    @Test public void crampedTextCardNeverGetsAnOverlappingBadge(){SocialPost p=new SocialPost(SocialExtractor.NEWS,"A cut-off headline.",56,1900,942,2118,false,2010,2100);assertEquals(-1,SocialBadgePlacement.top(p,141,2340,2.8125f));}
}

package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;

public class SocialPolicyTest {
    @Test public void independentEventAndSpecificPhotoClaimsAreDifferentScopes(){assertTrue(SocialPolicy.visualClaim("This photo shows the bridge collapsing today."));assertTrue(SocialPolicy.visualClaim("The video is from Mumbai."));assertFalse(SocialPolicy.visualClaim("A bridge collapsed in Mumbai. Tragic news."));assertFalse(SocialPolicy.visualClaim("The government announced a new policy for video games."));}
    @Test public void collapsedXCanCheckOnlyCompleteVisibleSentences(){
        assertTrue(SocialPolicy.visibleTextScope(new SocialPost(SocialExtractor.X,"The spacecraft launched on Tuesday. #Space Show more",0,0,100,100,true,false,-1,-1,java.util.Collections.emptyList())));
        assertFalse(SocialPolicy.visibleTextScope(new SocialPost(SocialExtractor.X,"The spacecraft launched with Show more",0,0,100,100,true,false,-1,-1,java.util.Collections.emptyList())));
        assertFalse(SocialPolicy.visibleTextScope(new SocialPost(SocialExtractor.REDDIT,"The spacecraft launched on Tuesday. Show more",0,0,100,100,true,false,-1,-1,java.util.Collections.emptyList())));
    }
    @org.junit.Test public void headlineFragmentsCannotBeSilencedByTheMessageModel(){String t="IIT Bombay faculty member booked for caste abuse, suicide abetment";org.junit.Assert.assertFalse(SocialPolicy.localOpinionGate(new SocialPost(SocialExtractor.NEWS,t,0,0,500,200,false)));org.junit.Assert.assertTrue(SocialPolicy.localOpinionGate(new SocialPost(SocialExtractor.REDDIT,"Happy Diwali!",0,0,500,200,false)));}
    @Test public void localOpinionVetoCannotHideMixedFacts(){assertTrue(SocialPolicy.clearPersonal("Happy Diwali!"));assertTrue(SocialPolicy.clearPersonal("I hate this."));assertFalse(SocialPolicy.clearPersonal("I hate this because 10 people died."));assertFalse(SocialPolicy.clearPersonal("IIT Bombay faculty member booked for caste abuse"));}
    private SocialPost p(String text){return new SocialPost(SocialExtractor.X,text,0,100,300,400,false);}
    @Test public void neverCertifyCurrentRumoursFromMemory(){for(String s:new String[]{"Trump is dead","The minister has resigned","Modi died yesterday","यह आज की खबर है","Read https://example.com/story","Inflation increased 50%"}){assertTrue(s,SocialPolicy.live(s));assertFalse(SocialPolicy.decisiveQuick("false",1,false,p(s)));}}
    @Test public void badAndUncertainModelOutputsDoNotGetTicks(){assertTrue(SocialPolicy.decisiveQuick("false",.99,false,p("The Earth is flat.")));assertFalse(SocialPolicy.decisiveQuick("false",1,true,p("The Earth is flat.")));assertFalse(SocialPolicy.decisiveQuick("true",Double.NaN,false,p("The Earth is flat.")));assertFalse(SocialPolicy.decisiveQuick("true",.94,false,p("The Earth is flat.")));assertFalse(SocialPolicy.decisiveQuick("research",1,false,p("The Earth is flat.")));}
    @Test public void identitiesPreserveClaimChangingDetails(){assertNotEquals(p("The Earth is flat").key,p("The Earth is not flat").key);assertNotEquals(p("It has 10 moons").key,p("It has 11 moons").key);assertEquals(p("The Earth is flat").key,p("The Earth  is flat").key);assertNotEquals(p("The Earth is flat").key,new SocialPost(SocialExtractor.X,"The Earth is flat",0,0,1,1,true).key);}
    @Test public void noMediaMemoryVerdict(){assertFalse(SocialPolicy.quickEligible(new SocialPost(SocialExtractor.X,"The Earth is flat",0,0,1,1,true)));}
    @Test public void currentCacheExpiresQuickly(){assertEquals(60_000,SocialPolicy.ttl(p("Trump is dead")));}
    @Test public void stableFactsCanUseOlderSources(){assertTrue(SocialPolicy.live("Water covers 71% of Earth's surface."));assertFalse(SocialPolicy.freshEvidence("Water covers 71% of Earth's surface."));assertFalse(SocialPolicy.freshEvidence("Biden won the 2020 election."));assertTrue(SocialPolicy.freshEvidence("Trump is dead."));assertTrue(SocialPolicy.freshEvidence("The minister resigned today."));}
}

package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SocialFeedTest {
    private SocialPost post(String text,int y){return new SocialPost(SocialExtractor.NEWS,text,0,y,500,y+150,false);}
    @Test public void everyVisibleHeadlineGetsAnIndependentPause(){
        SocialFeed f=new SocialFeed();f.observe(Arrays.asList(post("Earth is round.",100),post("Mars has two moons.",300),post("The Sun is a star.",500)),0);
        assertEquals(3,f.readings().size());
        for(SocialFeed.Reading r:f.readings()){assertNull(r.begin(SocialDwell.PAUSE_MS-1));SocialDwell.Ticket t=r.begin(SocialDwell.PAUSE_MS);assertNotNull(t);assertTrue(f.accepts(r,t));assertNull(r.begin(SocialDwell.PAUSE_MS*2));}
    }
    @Test public void removingOneCardDoesNotInvalidateItsNeighbor(){
        SocialFeed f=new SocialFeed();SocialPost a=post("Earth is round.",100),b=post("Mars has two moons.",300);f.observe(Arrays.asList(a,b),0);
        SocialFeed.Reading first=f.readings().get(0),second=f.readings().get(1);SocialDwell.Ticket ta=first.begin(SocialDwell.PAUSE_MS),tb=second.begin(SocialDwell.PAUSE_MS);
        f.observe(Collections.singletonList(b),SocialDwell.PAUSE_MS+50);assertFalse(f.accepts(first,ta));assertTrue(f.accepts(second,tb));
    }
    @Test public void reappearingIdenticalTextCannotAcceptAnOldCallback(){
        SocialFeed f=new SocialFeed();SocialPost a=post("Earth is round.",100);f.observe(Collections.singletonList(a),0);SocialFeed.Reading old=f.readings().get(0);SocialDwell.Ticket t=old.begin(SocialDwell.PAUSE_MS);
        f.observe(Collections.emptyList(),SocialDwell.PAUSE_MS+50);f.observe(Collections.singletonList(a),SocialDwell.PAUSE_MS+100);assertFalse(f.accepts(old,t));assertNotSame(old,f.readings().get(0));
    }
    @Test public void movingSubmittedCardKeepsItsTicketAndUpdatesItsAnchor(){
        SocialFeed f=new SocialFeed();SocialPost a=post("Earth is round.",100),b=post("Mars has two moons.",300);f.observe(Arrays.asList(a,b),0);
        SocialFeed.Reading first=f.readings().get(0),second=f.readings().get(1);SocialDwell.Ticket ta=first.begin(SocialDwell.PAUSE_MS),tb=second.begin(SocialDwell.PAUSE_MS);long moved=SocialDwell.PAUSE_MS+50;
        f.observe(Arrays.asList(post("Earth is round.",150),b),moved);assertTrue(f.accepts(first,ta));assertTrue(f.accepts(second,tb));assertEquals(150,first.post().top);assertNull(first.begin(moved+SocialDwell.PAUSE_MS));
    }
    @Test public void leavingTheFeedInvalidatesAllCards(){SocialFeed f=new SocialFeed();f.observe(Collections.singletonList(post("Earth is round.",100)),0);SocialFeed.Reading r=f.readings().get(0);SocialDwell.Ticket t=r.begin(SocialDwell.PAUSE_MS);f.clear();assertFalse(f.accepts(r,t));assertTrue(f.readings().isEmpty());}
    @Test public void identicalHeadlinesHaveSeparateBadgesAndSurvivePriorityReordering(){
        SocialFeed f=new SocialFeed();SocialPost a=post("Earth is round.",100),b=post("Earth is round.",300);f.observe(Arrays.asList(a,b),0);
        assertEquals(2,f.readings().size());SocialFeed.Reading first=f.readings().get(0),second=f.readings().get(1);SocialDwell.Ticket ta=first.begin(SocialDwell.PAUSE_MS),tb=second.begin(SocialDwell.PAUSE_MS);
        f.observe(Arrays.asList(b,a),SocialDwell.PAUSE_MS+50);assertTrue(f.accepts(first,ta));assertTrue(f.accepts(second,tb));assertSame(second,f.readings().get(0));
    }
}

package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
public class SocialDwellTest {
    private SocialPost post(String text,int y){return new SocialPost(SocialExtractor.REDDIT,text,0,y,300,y+120,false);}
    @Test public void waitsForPauseAndSubmitsOnlyOnce(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);assertNull(d.begin(SocialDwell.PAUSE_MS-1));assertNotNull(d.begin(SocialDwell.PAUSE_MS));assertNull(d.begin(SocialDwell.PAUSE_MS*2));}
    @Test public void recycledViewCannotInheritResult(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);SocialDwell.Ticket t=d.begin(SocialDwell.PAUSE_MS);long changed=SocialDwell.PAUSE_MS+50;d.observe(post("The Earth is round.",100),changed);assertFalse(d.accepts(t));assertNull(d.begin(changed+SocialDwell.PAUSE_MS-1));assertNotNull(d.begin(changed+SocialDwell.PAUSE_MS));}
    @Test public void scrollAndReturnInvalidateOldTicket(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);SocialDwell.Ticket t=d.begin(SocialDwell.PAUSE_MS);d.reset(SocialDwell.PAUSE_MS+50);d.observe(post("The Earth is flat.",100),SocialDwell.PAUSE_MS+100);assertFalse(d.accepts(t));}
    @Test public void movementBeforeSubmissionRestartsReadingPause(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);long moved=SocialDwell.PAUSE_MS-1;d.observe(post("The Earth is flat.",140),moved);assertNull(d.begin(moved+SocialDwell.PAUSE_MS-1));assertNotNull(d.begin(moved+SocialDwell.PAUSE_MS));}
    @Test public void submittedTicketSurvivesMovementAndUsesNewAnchor(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);SocialDwell.Ticket t=d.begin(SocialDwell.PAUSE_MS);d.observe(post("The Earth is flat.",140),SocialDwell.PAUSE_MS+50);assertTrue(d.accepts(t));assertEquals(140,d.current().top);assertNull(d.begin(SocialDwell.PAUSE_MS*2));}
    @Test public void leavingAppInvalidatesResult(){SocialDwell d=new SocialDwell();d.observe(post("The Earth is flat.",100),0);SocialDwell.Ticket t=d.begin(SocialDwell.PAUSE_MS);d.observe(null,SocialDwell.PAUSE_MS+50);assertFalse(d.accepts(t));}
}

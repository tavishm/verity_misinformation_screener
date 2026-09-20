package org.fairc.forwardcheck.social;

import org.junit.Test;
import static org.junit.Assert.*;

public class SocialScreeningServiceTest {
    @Test public void claimIdentityNormalizesVisibleWhitespace(){
        assertTrue(SocialScreeningService.sameClaimText("Earth is\n71% water", "  Earth   is 71% water "));
        assertFalse(SocialScreeningService.sameClaimText("Earth is 71% water", "Earth is 72% water"));
    }
    @Test public void reportRoundTripRequiresOriginalAppAndWindow(){
        SocialScreeningService.ReportRoundTrip state=new SocialScreeningService.ReportRoundTrip();state.begin("com.twitter.android",42,100);
        assertTrue(state.suspend(200,false));assertTrue(state.suspend(500,true));assertTrue(state.seenReport);
        assertTrue(state.returnedTo("com.twitter.android",42));assertFalse(state.active);
        state.begin("com.twitter.android",42,1000);state.suspend(1100,true);
        assertFalse(state.returnedTo("com.android.chrome",9));assertFalse(state.active);
    }
    @Test public void reportLaunchGraceExpiresWithoutLeakingState(){
        SocialScreeningService.ReportRoundTrip state=new SocialScreeningService.ReportRoundTrip();state.begin("com.reddit.frontpage",7,100);
        assertTrue(state.waitingToLaunch(3000));state.expire(100+SocialScreeningService.ReportRoundTrip.LAUNCH_GRACE_MS+1);
        assertFalse(state.active);assertFalse(state.suspend(9999,false));
    }
}

package org.fairc.forwardcheck;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SmsMessageTrackerTest {
    private List<MessageTracker.Item> items(boolean eligible,String... texts) {
        List<MessageTracker.Item> out=new ArrayList<>();
        for(String text:texts)out.add(new MessageTracker.Item(text,"node-"+out.size(),eligible));
        return out;
    }

    @Test public void harmlessNewestSmsDoesNotHideAnEarlierScam() {
        MessageTracker tracker=new MessageTracker();
        List<MessageTracker.Entry> rows=tracker.reconcile("sms",items(true,"credential-request","Happy Diwali"),true);
        tracker.settle(rows.get(1).id);
        rows=tracker.reconcile("sms",items(true,"credential-request","Happy Diwali"),true);
        assertFalse(rows.get(0).settled);assertTrue(rows.get(1).settled);
    }

    @Test public void asyncContactResolutionDoesNotSettleVisibleMessages() {
        MessageTracker tracker=new MessageTracker();
        List<MessageTracker.Entry> before=tracker.reconcile("sms",items(false,"scam","greeting"),true);
        List<MessageTracker.Entry> after=tracker.reconcile("sms",items(true,"scam","greeting"),true);
        assertEquals(before.get(0).id,after.get(0).id);
        assertFalse(after.get(0).settled);assertFalse(after.get(1).settled);
    }

    @Test public void ignoreVisibleBatchSurvivesRestartButANewSmsIsEligible() {
        MessageTracker tracker=new MessageTracker();
        tracker.settle(tracker.reconcile("sms",items(true,"scam","greeting"),true));
        MessageTracker restarted=new MessageTracker();restarted.restore(tracker.encode());
        List<MessageTracker.Entry> rows=restarted.reconcile("sms",items(true,"scam","greeting","new scam"),true);
        assertTrue(rows.get(0).settled);assertTrue(rows.get(1).settled);assertFalse(rows.get(2).settled);
    }

    @Test public void repeatedSmsCopyGetsItsOwnChoiceWhenBothVisible() {
        MessageTracker tracker=new MessageTracker();
        tracker.settle(tracker.reconcile("sms",items(true,"same scam"),true));
        List<MessageTracker.Entry> rows=tracker.reconcile("sms",items(true,"same scam","same scam"),true);
        assertTrue(rows.get(0).settled);assertFalse(rows.get(1).settled);assertNotEquals(rows.get(0).id,rows.get(1).id);
    }

    @Test public void whatsappAndSmsChoicesRemainSeparate() {
        MessageTracker tracker=new MessageTracker();tracker.settle(tracker.reconcile("whatsapp",items(true,"claim")));
        List<MessageTracker.Entry> sms=tracker.reconcile("sms",items(true,"claim","greeting"),true);
        assertFalse(sms.get(0).settled);assertTrue(tracker.reconcile("whatsapp",items(true,"claim")).get(0).settled);
        assertTrue(tracker.reconcile("other-whatsapp",items(true,"old","new")).get(0).settled);
    }
}

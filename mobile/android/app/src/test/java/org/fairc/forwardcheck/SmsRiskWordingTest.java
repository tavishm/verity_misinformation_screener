package org.fairc.forwardcheck;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsRiskWordingTest {
    @Test public void urgentHindiBankInformationRequestWarns() {
        assertFalse(SpamPolicy.reason("कृपया अपनी बैंक जानकारी तुरंत भेजें।",0,1).isEmpty());
        assertFalse(SpamPolicy.reason("कृपया अपना बैंक विवरण अभी साझा करें।",0,1).isEmpty());
    }
    @Test public void sameRequestInEnglishWarns() {
        assertFalse(SpamPolicy.reason("Please share your bank details immediately.",0,1).isEmpty());
    }
    @Test public void safetyAdviceAndOrdinaryBankingTalkStayQuiet() {
        assertTrue(SpamPolicy.reason("Do not share your bank details immediately when someone calls.",0,1).isEmpty());
        assertTrue(SpamPolicy.reason("सावधान! अपनी बैंक जानकारी तुरंत मत भेजें।",0,1).isEmpty());
        assertTrue(SpamPolicy.reason("My bank details have changed.",0,1).isEmpty());
    }
}

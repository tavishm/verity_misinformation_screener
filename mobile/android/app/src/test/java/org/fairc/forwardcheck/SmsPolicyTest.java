package org.fairc.forwardcheck;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsPolicyTest {
    @Test public void requiresEnabledIncomingIndividualAndConfirmedUnknownSender() {
        for(boolean enabled:new boolean[]{false,true})
            for(boolean incoming:new boolean[]{false,true})
                for(boolean group:new boolean[]{false,true})
                    for(SmsPolicy.ContactStatus sender:SmsPolicy.ContactStatus.values())
                        assertEquals(enabled&&incoming&&!group&&sender==SmsPolicy.ContactStatus.UNKNOWN,
                                SmsPolicy.eligible(enabled,incoming,group,sender));
    }

    @Test public void factualUncertainAndLinkRoutesNeverBecomeSmsWarnings() {
        for(String action:new String[]{null,"","personal_skip","opinion_skip","factual_offer","uncertain_offer","offer_link_check"})
            assertFalse("Unexpected SMS warning for "+action,SmsPolicy.warn(action));
        assertTrue(SmsPolicy.warn("spam_warning"));
    }

    @Test public void supportsOnlyTheImplementedGoogleMessagesAdapter() {
        assertTrue(SmsPolicy.supported("com.google.android.apps.messaging"));
        for(String app:new String[]{null,"","com.whatsapp","com.samsung.android.messaging","com.example.messages"})
            assertFalse(SmsPolicy.supported(app));
    }

    @Test public void englishHindiAndHinglishCredentialRequestsWarn() {
        for(String text:new String[]{"Please share your UPI PIN with our support team.",
                "अपना बैंक का पासवर्ड हमें भेजें।","Apna OTP hame bhejo."})
            assertFalse(text,SpamPolicy.reason(text,0,1).isEmpty());
    }

    @Test public void normalCodesAndSafetyAdviceAreNotScamRequests() {
        for(String text:new String[]{"Your OTP is 123456. Do not share this code.",
                "आपका ओटीपी 123456 है। इसे किसी को मत बताएं।",
                "Do not send your password to anyone.","OTP kisi ko mat bhejo.",
                "Happy Diwali!","दीवाली की शुभकामनाएं!","The Earth is flat.",
                "Please read https://example.org/news"})
            assertEquals(text,"",SpamPolicy.reason(text,1,.9));
    }

    @Test public void prizePaymentAndThreateningBankLinksStillWarn() {
        assertFalse(SpamPolicy.reason("Pay a processing fee to receive your lottery prize.",0,1).isEmpty());
        assertFalse(SpamPolicy.reason("Your bank account will be blocked. Click https://example.org/kyc to verify immediately.",0,1).isEmpty());
    }
}

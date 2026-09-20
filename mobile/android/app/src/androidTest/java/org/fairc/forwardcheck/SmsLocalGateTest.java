package org.fairc.forwardcheck;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local-only tests. No private inbox, contact edits, UI navigation, or paid calls. */
@RunWith(AndroidJUnit4.class)
public class SmsLocalGateTest {
    @Test public void credentialRequestsInBothLanguagesWarn() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String text:new String[]{"Please share your UPI PIN with our support team.",
                "अपना बैंक का पासवर्ड हमें भेजें।","Apna OTP hame bhejo."})
            assertTrue(text,SmsPolicy.warn(ScreeningClassifier.classify(app,text).action));
    }

    @Test public void greetingsNormalCodesLinksAndFactsHaveNoSmsFactCheckRoute() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String text:new String[]{"Happy Diwali!","दिवाली की शुभकामनाएं!",
                "Your OTP is 123456. Do not share this code.",
                "आपका ओटीपी 123456 है। इसे किसी को मत बताएं।",
                "The Earth is flat.","पृथ्वी सपाट है।","Please read https://example.org/news"})
            assertFalse(text,SmsPolicy.warn(ScreeningClassifier.classify(app,text).action));
    }

    @Test public void missingContactsPermissionNeverStartsALookupOrAssumesUnknown() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context denied=new ContextWrapper(app){
            @Override public Context getApplicationContext(){return this;}
            @Override public int checkSelfPermission(String permission){return PackageManager.PERMISSION_DENIED;}
        };
        AtomicBoolean queued=new AtomicBoolean();
        SmsSenderLookup lookup=new SmsSenderLookup(denied,new Handler(Looper.getMainLooper()),
                job->queued.set(true),()->{});
        try{
            assertEquals(SmsPolicy.ContactStatus.UNRESOLVED,lookup.status("+1 555 000 1000"));
            assertFalse(queued.get());
        }finally{lookup.close();}
    }
}

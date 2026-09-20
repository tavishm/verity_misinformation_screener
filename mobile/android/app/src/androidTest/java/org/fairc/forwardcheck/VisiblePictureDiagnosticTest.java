package org.fairc.forwardcheck;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicitly opted-in screen diagnostic. Never saves or uploads pixels/text. */
@RunWith(AndroidJUnit4.class)
public class VisiblePictureDiagnosticTest {
    @Test public void visibleFactualPictureReachesLocalScreening() throws Exception {
        Assume.assumeTrue("Requires an owner's visible factual test picture",
                "true".equals(InstrumentationRegistry.getArguments().getString("inspect_visible_picture")));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        android.app.UiAutomation automation=instrumentation.getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                |android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                |android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        AccessibilityNodeInfo root=null;
        // Automation connects asynchronously and may initially have no active
        // window even while WhatsApp is plainly visible. Wait for that binding.
        for(int attempt=0;attempt<40 && root==null;attempt++) {
            root=automation.getRootInActiveWindow();
            if(root==null)android.os.SystemClock.sleep(100);
        }
        assertNotNull("Leave the test chat visible",root);
        ChatSnapshot frame;
        try {
            assertTrue("Leave WhatsApp visible",String.valueOf(root.getPackageName()).startsWith("com.whatsapp"));
            frame=ChatSnapshot.read(root,new ChosenContacts(context));
        } finally { root.recycle(); }
        ChatSnapshot.Bubble candidate=null;
        for(ChatSnapshot.Bubble bubble:frame.bubbles)if(bubble.image!=null && bubble.eligible)candidate=bubble;
        assertNotNull("No eligible picture exposed by WhatsApp",candidate);
        Bitmap screen=automation.takeScreenshot();
        assertNotNull("Android did not expose screenshot pixels",screen);
        Bitmap cropped=null;
        try {
            Rect bounds=new Rect(candidate.image);
            assertTrue(bounds.intersect(0,0,screen.getWidth(),screen.getHeight()));
            cropped=Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height());
            long start=System.nanoTime();
            ImageScreen.Result read=ImageScreen.get(context).screen(cropped);
            long elapsed=(System.nanoTime()-start)/1000000;
            assertTrue("No readable words in test picture",read.text.length()>=20);
            String route=ScreeningClassifier.classify(context,read.text).action;
            assertTrue("Factual test picture was skipped: "+route,
                    "factual_offer".equals(route)||"offer_link_check".equals(route)||"spam_warning".equals(route));
            Bundle status=new Bundle();status.putInt("ocr_characters",read.text.length());
            status.putLong("ocr_elapsed_ms",elapsed);status.putString("local_action",route);
            instrumentation.sendStatus(0,status);
        } finally { if(cropped!=null && cropped!=screen)cropped.recycle();screen.recycle(); }
    }
}

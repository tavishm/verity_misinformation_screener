package org.fairc.forwardcheck.social;

import android.content.Context;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import org.fairc.forwardcheck.AppPrefs;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Emulator-only key setup from an ephemeral private file. Never log the key. */
public final class SocialEmulatorSetupTest {
    @Test public void configureTestKey()throws Exception{
        assertEquals("This setup is for an Android emulator only.","ranchu",Build.HARDWARE);
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File secret=new File(c.getFilesDir(),"social-test-key");assertTrue(secret.exists());
        try{String key=new String(java.nio.file.Files.readAllBytes(secret.toPath()),StandardCharsets.UTF_8).trim();new AppPrefs(c).setApiKey(key);}
        finally{assertTrue(secret.delete());}
        assertTrue(new AppPrefs(c).paired());
    }
}

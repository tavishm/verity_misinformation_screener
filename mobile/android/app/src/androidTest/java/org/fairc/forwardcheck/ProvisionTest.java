package org.fairc.forwardcheck;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
/** Test APK only. Reads a single private, adb-provisioned secret and deletes it. */
@RunWith(AndroidJUnit4.class)
public class ProvisionTest {
    @Test public void importPrivateKey()throws Exception{
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File f=new File(c.getFilesDir(),"provision-once");Assume.assumeTrue(f.isFile());
        try{String value=new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();new AppPrefs(c).setApiKey(value);assertTrue(new AppPrefs(c).paired());}finally{assertTrue(f.delete());}
    }
}

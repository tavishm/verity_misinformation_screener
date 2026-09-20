package org.fairc.forwardcheck;
import android.content.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
/** Regressions for the actual error-screen crash and interrupted-job recreation. No network calls. */
@RunWith(AndroidJUnit4.class)
public class ActivityRecoveryTest {
    private boolean text(View root,String expected){if(root instanceof TextView && expected.contentEquals(((TextView)root).getText()))return true;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(text(((ViewGroup)root).getChildAt(i),expected))return true;return false;}
    @Test public void missingJobShowsRecoverableErrorAcrossRecreation()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Intent intent=new Intent(c,ConsentActivity.class).putExtra("job_id","missing-test-job").putExtra(MainActivity.EXTRA_SOURCE,"manual");
        try(ActivityScenario<ConsentActivity> scenario=ActivityScenario.launch(intent)){
            Thread.sleep(400);scenario.onActivity(a->assertTrue(text(a.getWindow().getDecorView(),I18n.text(a,"Could not check"))));
            scenario.recreate();Thread.sleep(400);scenario.onActivity(a->assertTrue(text(a.getWindow().getDecorView(),I18n.text(a,"Could not check"))));
        }
    }
}

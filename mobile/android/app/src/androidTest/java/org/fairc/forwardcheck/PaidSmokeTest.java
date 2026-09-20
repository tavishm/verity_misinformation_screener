package org.fairc.forwardcheck;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class PaidSmokeTest {
    @Test public void directPhoneQuickCheck()throws Exception{
        Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("paid")));
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PhoneResearch research=PhoneResearch.get(c);
        long start=System.currentTimeMillis();String id=research.start("The Earth is flat.",true,false);JSONObject job;
        do{Thread.sleep(200);job=research.job(id);}while("pending".equals(job.optString("status"))&&System.currentTimeMillis()-start<65000);
        assertEquals(job.optString("error"),"complete",job.optString("status"));assertEquals("Looks false",job.getJSONObject("result").optString("label"));
        System.out.println("DIRECT_PHONE_QUICK_MS="+(System.currentTimeMillis()-start));System.out.println(new PhoneCosts(c).summary());
    }
    @Test public void directPhoneResearch()throws Exception{
        Assume.assumeTrue("research".equals(InstrumentationRegistry.getArguments().getString("paid")));
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();PhoneResearch research=PhoneResearch.get(c);
        long start=System.currentTimeMillis();String id=research.start("Antibiotics kill viruses.",true,true);JSONObject job;
        do{Thread.sleep(300);job=research.job(id);}while("pending".equals(job.optString("status"))&&System.currentTimeMillis()-start<85000);
        assertEquals(job.optString("error"),"complete",job.optString("status"));JSONObject result=job.getJSONObject("result");
        System.out.println("DIRECT_PHONE_RESEARCH_MS="+(System.currentTimeMillis()-start));System.out.println("RESEARCH_LABEL="+result.optString("label"));System.out.println(new PhoneCosts(c).summary());
        assertEquals("Looks false",result.optString("label"));assertTrue(result.getJSONArray("claims").getJSONObject(0).getJSONArray("evidence").length()>0);
    }
}

package org.fairc.forwardcheck;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Cross-runtime parity, real device latency, and context-sensitive routing. */
@RunWith(AndroidJUnit4.class)
public class ContextGateTest {
    @Test public void tokenizerAndQuantizedModelMatchReference()throws Exception {
        Context phone=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context tests=InstrumentationRegistry.getInstrumentation().getContext();
        String json;
        try(InputStream in=tests.getAssets().open("context-parity.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);
            json=new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
        ContextTokenizer tokenizer=new ContextTokenizer(phone.getAssets().open("context-vocab.txt"));
        long started=SystemClock.elapsedRealtime();ContextGate gate=ContextGate.get(phone);
        long load=SystemClock.elapsedRealtime()-started;
        JSONArray examples=new JSONArray(json);ArrayList<Long> times=new ArrayList<>();double largest=0;
        for(int i=0;i<examples.length();i++){
            JSONObject example=examples.getJSONObject(i);String text=example.getString("text");
            int[] actual=tokenizer.encode(text,128);JSONArray expected=example.getJSONArray("token_ids");
            assertEquals("token count for example "+i,expected.length(),actual.length);
            for(int j=0;j<actual.length;j++)assertEquals("token for example "+i,expected.getInt(j),actual[j]);
            started=SystemClock.elapsedRealtime();double[] p=gate.scoreUncached(text);times.add(SystemClock.elapsedRealtime()-started);
            JSONArray target=example.getJSONArray("probabilities");
            for(int j=0;j<3;j++){largest=Math.max(largest,Math.abs(p[j]-target.getDouble(j)));assertEquals("probability for example "+i,target.getDouble(j),p[j],.04);}
        }
        Collections.sort(times);android.os.Bundle result=new android.os.Bundle();
        result.putLong("context_model_load_ms",load);result.putLong("context_median_inference_ms",times.get(times.size()/2));
        result.putLong("context_p95_inference_ms",times.get((times.size()*95)/100));result.putDouble("largest_probability_difference",largest);
        result.putInt("parity_examples",examples.length());InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }

    @Test public void preservesClaimsInsideFriendlyMessagesAndHandlesHindi() {
        Context phone=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("personal_skip",ScreeningClassifier.classify(phone,"Happy Diwali! Wishing you and your family joy.").action);
        assertEquals("factual_offer",ScreeningClassifier.classify(phone,"Happy Diwali! Drinking hot water changes your sexual orientation.").action);
        assertEquals("spam_warning",ScreeningClassifier.classify(phone,"अपना बैंक का पासवर्ड हमें भेजें।").action);
        assertEquals("offer_link_check",ScreeningClassifier.classify(phone,"Please read https://example.org/news").action);
    }
    @Test public void compareRoutingOnFixedDevelopmentExamples()throws Exception {
        Context phone=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context tests=InstrumentationRegistry.getInstrumentation().getContext();
        JSONArray rows;
        try(InputStream in=tests.getAssets().open("routing-development.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);
            rows=new JSONArray(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }
        JSONObject report=new JSONObject();
        for(String language:new String[]{"en","hi","hinglish"}){
            int[][] before=new int[3][3],after=new int[3][3];
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);if(!language.equals(row.getString("language")))continue;
                int expected="personal".equals(row.getString("label"))?0:"claim".equals(row.getString("label"))?1:2;
                String text=row.getString("text");before[expected][category(LocalClassifier.classify(phone,text).action)]++;
                after[expected][category(ScreeningClassifier.classify(phone,text).action)]++;
            }
            report.put(language,new JSONObject().put("old_gate",new JSONArray(before)).put("new_gate",new JSONArray(after)));
        }
        android.os.Bundle result=new android.os.Bundle();result.putString("development_comparison_not_field_accuracy",report.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(0,result);
    }
    private int category(String action){return "spam_warning".equals(action)?2:("personal_skip".equals(action)||"opinion_skip".equals(action))?0:1;}
}

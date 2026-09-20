package org.fairc.forwardcheck;

import android.content.Context;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Contextual English/Hindi routing. This model never judges whether a claim is true. */
final class ContextGate {
    private static ContextGate singleton;
    static synchronized ContextGate get(Context context)throws Exception{if(singleton==null)singleton=new ContextGate(context.getApplicationContext());return singleton;}
    private final ContextTokenizer tokenizer;
    private final OrtSession session;
    private final double personalThreshold,spamThreshold;
    private final LinkedHashMap<String,double[]> cache=new LinkedHashMap<String,double[]>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,double[]> e){return size()>256;}};
    private ContextGate(Context c)throws Exception{
        JSONObject metadata;try(InputStream input=c.getAssets().open("context-head.json")){metadata=new JSONObject(new String(read(input),java.nio.charset.StandardCharsets.UTF_8));}
        if(metadata.optInt("version")!=2 || !"logits".equals(metadata.optString("output")))throw new IOException("Wrong routing model format");
        tokenizer=new ContextTokenizer(c.getAssets().open("context-vocab.txt"));personalThreshold=metadata.getDouble("personal_threshold");spamThreshold=metadata.getDouble("spam_threshold");
        File path=new File(c.getFilesDir(),"context-encoder-int8.onnx");String expected=metadata.getString("encoder_sha256");
        if(!path.isFile() || !expected.equals(c.getSharedPreferences("model_files",Context.MODE_PRIVATE).getString("context_encoder_sha",""))){
            File temp=new File(c.getFilesDir(),"context-encoder.part");java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream input=c.getAssets().open("context-encoder-int8.onnx");OutputStream out=new FileOutputStream(temp)){byte[] buf=new byte[65536];for(int n;(n=input.read(buf))!=-1;){digest.update(buf,0,n);out.write(buf,0,n);}}
            StringBuilder checksum=new StringBuilder();for(byte b:digest.digest())checksum.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!expected.contentEquals(checksum)){temp.delete();throw new IOException("Context model checksum mismatch");}
            if(!temp.renameTo(path))throw new IOException("Could not save context model");c.getSharedPreferences("model_files",Context.MODE_PRIVATE).edit().putString("context_encoder_sha",expected).commit();
        }
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);session=OrtEnvironment.getEnvironment().createSession(path.toString(),options);}
    }
    synchronized double[] score(String text)throws Exception{
        String key=ChatSnapshot.hash(text);double[] cached=cache.get(key);if(cached!=null)return cached.clone();
        double[] scores=scoreUncached(text);cache.put(key,scores);return scores.clone();
    }
    synchronized double[] scoreUncached(String text)throws Exception{
        int[] ids=tokenizer.encode(text,128);long[][] input=new long[1][ids.length],mask=new long[1][ids.length];for(int i=0;i<ids.length;i++){input[0][i]=ids[i];mask[0][i]=1;}
        double[] logits=new double[3];
        try(OnnxTensor tokens=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),input);OnnxTensor attention=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),mask)){
            Map<String,OnnxTensor> feed=new HashMap<>();feed.put("input_ids",tokens);feed.put("attention_mask",attention);
            try(OrtSession.Result result=session.run(feed)){float[][] output=(float[][])result.get(0).getValue();if(output.length!=1 || output[0].length!=3)throw new IOException("Wrong routing output");for(int k=0;k<3;k++)logits[k]=output[0][k];}
        }
        double max=Math.max(logits[0],Math.max(logits[1],logits[2]));
        for(double value:logits)if(!Double.isFinite(value))throw new IOException("Invalid routing output");
        double sum=0;for(int k=0;k<3;k++){logits[k]=Math.exp(logits[k]-max);sum+=logits[k];}for(int k=0;k<3;k++)logits[k]/=sum;return logits;
    }
    LocalClassifier.Decision classify(String text)throws Exception{
        double[] score=score(text);
        if(score[2]>=spamThreshold)return new LocalClassifier.Decision("spam_warning","Possible scam","This message may be trying to get money or private details.",score[2]);
        // A long message must not be silently skipped because its factual tail was truncated.
        if(tokenizer.encode(text,130).length>=130)return new LocalClassifier.Decision("factual_offer","Long message","Ask before checking this message.",score[1]);
        if(score[0]>=personalThreshold)return new LocalClassifier.Decision("personal_skip","Personal message","This looks like a personal message or opinion.",score[0]);
        return new LocalClassifier.Decision("factual_offer","Worth checking","Ask before checking this message.",score[1]);
    }
    private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
}

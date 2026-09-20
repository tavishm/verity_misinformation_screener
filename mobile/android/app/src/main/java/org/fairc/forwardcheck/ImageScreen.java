package org.fairc.forwardcheck;
import android.content.Context;
import android.graphics.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** All image processing is local. No screenshot or image is sent to a provider. */
final class ImageScreen {
    static final class Result { final String text; final float aiScore; Result(String t,float s){text=t;aiScore=s;} }
    private static ImageScreen instance;
    static synchronized ImageScreen get(Context c) throws Exception { if(instance==null)instance=new ImageScreen(c.getApplicationContext());return instance; }
    private final Context context;
    private final TextRecognizer latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final TextRecognizer hindi=TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
    private OrtSession session;
    private ImageScreen(Context c) {context=c;}
    synchronized Result screen(Bitmap bitmap) throws Exception {
        InputImage input=InputImage.fromBitmap(bitmap,0);
        com.google.android.gms.tasks.Task<Text> en=latin.process(input), hi=hindi.process(input);
        String a=Tasks.await(en,15,TimeUnit.SECONDS).getText(), b=Tasks.await(hi,15,TimeUnit.SECONDS).getText();
        long devanagari=b.chars().filter(ch->ch>=0x0900 && ch<=0x097f).count();
        String text=devanagari>2?b:a;
        float score=Float.NaN;
        // Posters, screenshots and diagrams are outside this photographic model's scope.
        if(text.trim().length()<30)try {score=aiScore(bitmap);}catch(Exception ignored){}
        return new Result(text.length()>5000?text.substring(0,5000):text,score);
    }
    synchronized float aiScore(Bitmap bitmap) throws Exception {
        if(session==null) {
            File file=new File(context.getFilesDir(),"ai-image-quantized.onnx");
            if(!file.isFile() || file.length()!=15258532) { try(InputStream in=context.getAssets().open("ai-image-quantized.onnx"); OutputStream out=new FileOutputStream(file)) { byte[] buf=new byte[65536]; for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n); } }
            try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()) { options.setIntraOpNumThreads(2); session=OrtEnvironment.getEnvironment().createSession(file.toString(),options); }
        }
        Bitmap resized=Bitmap.createScaledBitmap(bitmap,224,224,true); int[] pixels=new int[224*224]; resized.getPixels(pixels,0,224,0,0,224,224); if(resized!=bitmap)resized.recycle();
        float[] values=new float[pixels.length*3]; for(int i=0;i<pixels.length;i++){values[i]=((pixels[i]>>16)&255)/127.5f-1;values[i+pixels.length]=((pixels[i]>>8)&255)/127.5f-1;values[i+pixels.length*2]=(pixels[i]&255)/127.5f-1;}
        try(OnnxTensor tensor=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),FloatBuffer.wrap(values),new long[]{1,3,224,224}); OrtSession.Result out=session.run(Collections.singletonMap(session.getInputNames().iterator().next(),tensor))) {
            float[][] logits=(float[][])out.get(0).getValue(); double d=logits[0][1]-logits[0][0];return (float)(1/(1+Math.exp(d)));
        }
    }
    static String aiNote(float score) { return Float.isNaN(score)?"":"Experimental AI image check: " + (score>=.98f?"may be made with AI. This does not tell us whether the message is true.":"cannot tell if this image was made with AI."); }
}

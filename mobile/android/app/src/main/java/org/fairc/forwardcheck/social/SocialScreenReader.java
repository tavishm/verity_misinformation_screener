package org.fairc.forwardcheck.social;

import android.accessibilityservice.AccessibilityService;
import android.graphics.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

/** In-memory, on-device fallback for Reddit screens with no accessible post text. */
final class SocialScreenReader {
    private static final String TAG="ForwardCheckSocial";
    private static final long SCREENSHOT_COOLDOWN_MS=400;
    private static final int MAX_INTERVAL_RETRIES=2,MAX_EXPLICIT_WAITING=4;
    interface ReadCallback {void result(List<SocialPost> posts);}
    interface ImageCallback {void result(String jpegDataUrl);}
    private final AccessibilityService service;
    private final TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayDeque<CaptureRequest> explicitWaiting=new ArrayDeque<>();
    private final Runnable pumpTask=this::pump;
    private CaptureRequest waitingOcr,activeCapture;
    private long lastCaptureStarted=-SCREENSHOT_COOLDOWN_MS;
    private boolean closed;
    private int lastOcrWindow=-1;private long readingEpoch,lastOcrEpoch=-1;
    private List<SocialPost> lastOcrPosts=Collections.emptyList();
    SocialScreenReader(AccessibilityService service){this.service=service;}
    private interface Pixels {void result(Bitmap bitmap,boolean transientFailure);}
    private static final class CaptureRequest {
        final int windowId;final boolean explicit;final Pixels pixels;int intervalRetries;
        CaptureRequest(int windowId,boolean explicit,Pixels pixels){this.windowId=windowId;this.explicit=explicit;this.pixels=pixels;}
    }
    private void capture(int windowId,boolean explicit,Pixels pixels){
        if(Build.VERSION.SDK_INT<34){pixels.result(null,false);return;}
        main.post(()->enqueue(new CaptureRequest(windowId,explicit,pixels)));
    }
    private void enqueue(CaptureRequest request){
        if(closed){request.pixels.result(null,false);return;}
        if(request.explicit){
            if(explicitWaiting.size()>=MAX_EXPLICIT_WAITING){Log.w(TAG,"Screenshot queue full: type=image");request.pixels.result(null,false);return;}
            explicitWaiting.addLast(request);
        }else if(waitingOcr==null)waitingOcr=request;
        else {Log.w(TAG,"Screenshot queue full: type=ocr");request.pixels.result(null,true);return;}
        pump();
    }
    private void pump(){
        main.removeCallbacks(pumpTask);if(closed||activeCapture!=null)return;
        CaptureRequest request=!explicitWaiting.isEmpty()?explicitWaiting.pollFirst():waitingOcr;
        if(request==null)return;if(request==waitingOcr)waitingOcr=null;
        long wait=SCREENSHOT_COOLDOWN_MS-(SystemClock.elapsedRealtime()-lastCaptureStarted);
        if(wait>0){if(request.explicit)explicitWaiting.addFirst(request);else waitingOcr=request;main.postDelayed(pumpTask,wait);return;}
        activeCapture=request;lastCaptureStarted=SystemClock.elapsedRealtime();
        try{service.takeScreenshotOfWindow(request.windowId,service.getMainExecutor(),new AccessibilityService.TakeScreenshotCallback(){
            public void onSuccess(AccessibilityService.ScreenshotResult result){
                android.hardware.HardwareBuffer buffer=null;Bitmap hardware=null,copy=null;
                try{buffer=result.getHardwareBuffer();hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware!=null)copy=hardware.copy(Bitmap.Config.ARGB_8888,false);}
                catch(Exception ignored){}finally{if(hardware!=null)hardware.recycle();if(buffer!=null)buffer.close();}
                finish(request,copy,false);
            }
            public void onFailure(int error){
                if(error==AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT&&request.intervalRetries<MAX_INTERVAL_RETRIES&&!closed){
                    activeCapture=null;request.intervalRetries++;Log.w(TAG,"Screenshot retry: type="+(request.explicit?"image":"ocr")+", error="+error+", attempt="+request.intervalRetries);
                    if(request.explicit)explicitWaiting.addFirst(request);else waitingOcr=request;pump();return;
                }
                Log.w(TAG,"Screenshot failed: type="+(request.explicit?"image":"ocr")+", error="+error+", retries="+request.intervalRetries);
                finish(request,null,error==AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT);
            }
        });}catch(Exception error){finish(request,null,false);}
    }
    private void finish(CaptureRequest request,Bitmap bitmap,boolean transientFailure){
        if(request!=activeCapture){if(bitmap!=null)bitmap.recycle();return;}activeCapture=null;
        if(closed){if(bitmap!=null)bitmap.recycle();return;}
        try{request.pixels.result(bitmap,transientFailure);}catch(RuntimeException callbackError){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();Log.w(TAG,"Screenshot callback failed: type="+(request.explicit?"image":"ocr"));}
        pump();
    }
    void invalidateHistory(){readingEpoch++;lastOcrEpoch=-1;lastOcrPosts=Collections.emptyList();}
    void read(int windowId,int width,int height,ReadCallback callback){
        final long epoch=readingEpoch;
        capture(windowId,false,(bitmap,transientFailure)->{
            if(bitmap==null){callback.result(transientFailure&&epoch==readingEpoch&&lastOcrEpoch==epoch&&lastOcrWindow==windowId?new ArrayList<>(lastOcrPosts):Collections.emptyList());return;}
            recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener(text->{
                try{
                    List<SocialRedditOcr.Line> lines=new ArrayList<>();
                    float sx=(float)width/bitmap.getWidth(),sy=(float)height/bitmap.getHeight();
                    for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
                        List<Text.Element> words=new ArrayList<>(line.getElements());words.sort(Comparator.comparingInt(w->w.getBoundingBox()==null?0:w.getBoundingBox().left));
                        StringBuilder part=new StringBuilder();Rect bounds=null;
                        for(Text.Element word:words){Rect b=word.getBoundingBox();if(b==null)continue;
                            if(bounds!=null&&b.left-bounds.right>bitmap.getWidth()*.051){addLine(lines,part.toString(),bounds,sx,sy,line.getConfidence());part.setLength(0);bounds=null;}
                            if(part.length()>0)part.append(' ');part.append(word.getText());if(bounds==null)bounds=new Rect(b);else bounds.union(b);
                        }
                        if(bounds!=null)addLine(lines,part.toString(),bounds,sx,sy,line.getConfidence());
                        else if(line.getBoundingBox()!=null)addLine(lines,line.getText(),line.getBoundingBox(),sx,sy,line.getConfidence());
                    }
                    List<SocialPost> posts=SocialRedditOcr.extract(lines,width,height);if(epoch==readingEpoch){lastOcrWindow=windowId;lastOcrEpoch=epoch;lastOcrPosts=Collections.unmodifiableList(new ArrayList<>(posts));}callback.result(posts);
                }finally{bitmap.recycle();}
            }).addOnFailureListener(error->{bitmap.recycle();callback.result(Collections.emptyList());});
        });
    }
    private static void addLine(List<SocialRedditOcr.Line> lines,String text,Rect b,float sx,float sy,float confidence){lines.add(new SocialRedditOcr.Line(text,Math.round(b.left*sx),Math.round(b.top*sy),Math.round(b.right*sx),Math.round(b.bottom*sy),confidence>0?Math.round(confidence*100):80));}
    void image(int windowId,SocialPost post,ImageCallback callback){
        capture(windowId,true,(bitmap,transientFailure)->{if(bitmap==null){callback.result("");return;}
            worker.execute(()->{
                String result="";Bitmap crop=null,scaled=null;
                try{
                    int left=Math.max(0,post.left),top=Math.max(0,post.top),right=Math.min(bitmap.getWidth(),post.right),bottom=Math.min(bitmap.getHeight(),post.bottom);
                    if(right-left>=40&&bottom-top>=40){
                        crop=Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top);
                        double ratio=Math.min(1,1200.0/Math.max(crop.getWidth(),crop.getHeight()));
                        scaled=ratio<1?Bitmap.createScaledBitmap(crop,(int)(crop.getWidth()*ratio),(int)(crop.getHeight()*ratio),true):crop;
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,85,bytes);
                        if(bytes.size()<=750_000)result="data:image/jpeg;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
                    }
                }catch(Exception ignored){}finally{if(scaled!=null&&scaled!=crop)scaled.recycle();if(crop!=null&&crop!=bitmap)crop.recycle();bitmap.recycle();}
                final String data=result;service.getMainExecutor().execute(()->callback.result(data));
            });
        });
    }
    void close(){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::close);return;}
        if(closed)return;closed=true;main.removeCallbacks(pumpTask);
        while(!explicitWaiting.isEmpty())explicitWaiting.pollFirst().pixels.result(null,false);
        if(waitingOcr!=null){waitingOcr.pixels.result(null,false);waitingOcr=null;}
        recognizer.close();worker.shutdown();
    }
}

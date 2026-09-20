package org.fairc.forwardcheck;
import android.content.*;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class OnDeviceTest {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Bitmap poster(String text){Bitmap b=Bitmap.createBitmap(1400,420,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.WHITE);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(65);canvas.drawText(text,30,150,p);return b;}
    @Test public void localGateSeparatesPersonalAndScam(){String greeting=ScreeningClassifier.classify(context,"Happy Diwali! Wishing you and your family joy.").action;assertTrue(greeting, "personal_skip".equals(greeting)||"opinion_skip".equals(greeting));assertEquals("spam_warning",ScreeningClassifier.classify(context,"Please share your UPI PIN with our support team.").action);}
    @Test public void bundledEnglishOcr(){Bitmap b=poster("The Earth is flat.");try{ImageScreen.Result r=ImageScreen.get(context).screen(b);assertTrue(r.text,r.text.toLowerCase().contains("earth"));}catch(Exception e){throw new AssertionError(e);}finally{b.recycle();}}
    @Test public void bundledHindiOcr(){Bitmap b=poster("गरम पानी पीने से बीमारी ठीक होती है");try{ImageScreen.Result r=ImageScreen.get(context).screen(b);assertTrue(r.text,r.text.contains("पानी"));}catch(Exception e){throw new AssertionError(e);}finally{b.recycle();}}
    @Test public void selectedImageHonoursExifRotation()throws Exception{
        java.io.File file=java.io.File.createTempFile("image-orientation-",".jpg",context.getCacheDir());Bitmap original=Bitmap.createBitmap(120,240,Bitmap.Config.ARGB_8888);
        try{try(java.io.OutputStream out=new java.io.FileOutputStream(file)){original.compress(Bitmap.CompressFormat.JPEG,90,out);}
            android.media.ExifInterface exif=new android.media.ExifInterface(file.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
            Bitmap decoded=ImageReader.read(context,android.net.Uri.fromFile(file));try{assertEquals(240,decoded.getWidth());assertEquals(120,decoded.getHeight());}finally{decoded.recycle();}
        }finally{original.recycle();file.delete();}
    }
    @Test public void onnxInferenceProducesFiniteProbability()throws Exception{Bitmap b=poster(" ");long start=System.nanoTime();try{float score=ImageScreen.get(context).aiScore(b);assertTrue(Float.isFinite(score));assertTrue(score>=0&&score<=1);System.out.println("AI model inference ms="+(System.nanoTime()-start)/1000000);}finally{b.recycle();}}
    @Test public void noConsentMeansNoRequest()throws Exception{try{PhoneResearch.get(context).start("A test claim",false,false);fail("No-consent request started");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Tap"));}}
    @Test public void keyIsStoredEncrypted()throws Exception{
        // Separate preference/key alias is not available; do not overwrite the owner's real credential.
        KeyVault vault=new KeyVault(context);if(vault.configured()){String key=vault.read();assertTrue(key.length()>20);String stored=context.getSharedPreferences("api_secret",Context.MODE_PRIVATE).getAll().toString();assertFalse(stored.contains(key));}
    }
}

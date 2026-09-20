package org.fairc.forwardcheck;
import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Explicit image share/import fallback; reads only the selected content URI. */
public final class ImageActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(); private boolean destroyed;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        Ui ui=new Ui(this);LinearLayout box=ui.column();box.addView(ui.title("Reading the picture…"));box.addView(new ProgressBar(this));setContentView(ui.screen(box));
        Uri uri=getIntent().getData();
        worker.execute(()->{try {
            Bitmap bitmap=ImageReader.read(this,uri);
            ImageScreen.Result result;try{result=ImageScreen.get(this).screen(bitmap);}finally{bitmap.recycle();}
            String metadata="";
            try(InputStream in=getContentResolver().openInputStream(uri)) {android.media.ExifInterface exif=new android.media.ExifInterface(in);String software=exif.getAttribute("Software"); if(software!=null && software.matches("(?is).*(stable diffusion|midjourney|dall.e|firefly|comfyui).*"))metadata="The file names an AI tool. File details can be changed.";}catch(Exception ignored){}
            final String note=metadata.isEmpty()?ImageScreen.aiNote(result.aiScore):metadata;
            runOnUiThread(()->{if(destroyed)return;startActivity(new Intent(this,ConsentActivity.class).putExtra(MainActivity.EXTRA_TEXT,result.text.isEmpty()?"[Picture with no readable text]":result.text).putExtra(MainActivity.EXTRA_SOURCE,"image").putExtra("image_note",note).putExtra("image_no_text",result.text.trim().length()<3));finish();});
        }catch(Exception error){runOnUiThread(()->{if(destroyed)return;box.removeAllViews();box.addView(ui.title("Could not read this picture"));box.addView(ui.text("Try a clearer picture.",22));Button done=ui.button("Done",true);done.setOnClickListener(v->finish());box.addView(done);});}});
    }
    @Override public void onDestroy(){destroyed=true;worker.shutdownNow();super.onDestroy();}
}

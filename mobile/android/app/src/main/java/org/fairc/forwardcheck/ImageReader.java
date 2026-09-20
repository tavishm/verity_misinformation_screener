package org.fairc.forwardcheck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import java.io.IOException;

/** Bounded, software image decode; ImageDecoder applies the file's orientation. */
final class ImageReader {
    static Bitmap read(Context context,Uri uri)throws IOException {
        if(uri==null)throw new IOException("No picture selected");
        ImageDecoder.Source source=ImageDecoder.createSource(context.getContentResolver(),uri);
        return ImageDecoder.decodeBitmap(source,(decoder,info,input)->{
            int width=info.getSize().getWidth(),height=info.getSize().getHeight();
            if(width<1 || height<1)throw new IllegalArgumentException("Invalid picture size");
            double scale=Math.min(1.0,2048.0/Math.max(width,height));
            decoder.setTargetSize(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }
}

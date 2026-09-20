package org.fairc.forwardcheck.social;

/** Keep a badge inside its card and off the headline. All dimensions are px. */
public final class SocialBadgePlacement {
    public static int top(SocialPost post,int height,int screenHeight,float density){
        boolean compact=SocialExtractor.X.equals(post.app);
        int edge=Math.round((compact?4:8)*density),minimum=Math.max(Math.round(70*density),post.top+edge);
        int bottom=Math.min(screenHeight-Math.round(80*density),post.bottom-edge);
        int top=bottom-height;
        if(post.textBottom>=0&&top<post.textBottom+Math.round((compact?2:4)*density)){
            // A large lead image has room above the headline/publisher row.
            // Otherwise wait for a fully visible card instead of hiding words.
            top=minimum;
            if(top+height>post.textTop-Math.round(32*density))return -1;
        }
        return top>=minimum&&top+height<=bottom?top:-1;
    }
}

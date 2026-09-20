package org.fairc.forwardcheck.social;

import java.util.*;
import java.util.regex.*;

/**
 * Platform-free interpretation of OCR lines from Reddit. The Android adapter owns
 * capture/OCR; this class accepts only the observed public community/feed shape.
 */
final class SocialRedditOcr {
    private static final Pattern SUBREDDIT=Pattern.compile("(?i)^r/[a-z0-9_]{2,21}$");
    private static final Pattern AGE=Pattern.compile("(?i)(?:^|\\s)\\d{1,3}(?:m|h|d|w|y)(?:\\s|$)");
    private static final Pattern DOMAIN=Pattern.compile("(?i)(?:^|\\s)[a-z0-9-]+(?:\\.[a-z0-9-]+)+(?=\\s|$)");
    private static final Pattern COUNT=Pattern.compile("(?i)^\\d+(?:[.,]\\d+)?(?:k|m)?$");
    private static final Pattern SCALED_COUNT=Pattern.compile("(?i)^\\d+(?:[.,]\\d+)?(?:k|m)$");
    private static final Pattern COMMENTS=Pattern.compile("(?i)^\\s*(?:view all )?\\d*[.,]?\\d*[km]?\\s*comments?\\s*$|^comments?$");
    private static final Set<String> NAV=new HashSet<>(Arrays.asList("TOP POSTS","TOP POSTS ALL TIME","HOT","NEW","RISING","BEST","CONTROVERSIAL"));

    static final class Line {
        final String text; final int left,top,right,bottom,confidence; final boolean uncertain;
        Line(String text,int left,int top,int right,int bottom){this(text,left,top,right,bottom,100);}
        Line(String text,int left,int top,int right,int bottom,int confidence){this(text,left,top,right,bottom,confidence,confidence<85);}
        private Line(String text,int left,int top,int right,int bottom,int confidence,boolean uncertain){this.text=text==null?"":text.trim();this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.confidence=confidence;this.uncertain=uncertain;}
    }

    /** Parses Tesseract TSV word rows into geometrically ordered OCR lines. */
    static List<Line> tsv(String tsv){
        Map<String,List<Word>> grouped=new LinkedHashMap<>();
        String[] rows=tsv==null?new String[0]:tsv.split("\\r?\\n");
        for(int i=1;i<rows.length;i++){
            String[] c=rows[i].split("\\t",12);if(c.length<12||!"5".equals(c[0]))continue;
            try{String word=c[11].trim();int confidence=(int)Double.parseDouble(c[10]);if(word.isEmpty())continue;String key=c[1]+":"+c[2]+":"+c[3]+":"+c[4];grouped.computeIfAbsent(key,k->new ArrayList<>()).add(new Word(word,Integer.parseInt(c[6]),Integer.parseInt(c[7]),Integer.parseInt(c[8]),Integer.parseInt(c[9]),confidence));}catch(Exception ignored){}
        }
        List<Line> out=new ArrayList<>();for(List<Word> words:grouped.values()){
            words.sort(Comparator.comparingInt(w->w.left));List<Word> segment=new ArrayList<>();int previousRight=-1;
            for(Word word:words){if(previousRight>=0&&word.left-previousRight>55){addLine(out,segment);segment.clear();}segment.add(word);previousRight=Math.max(previousRight,word.left+word.width);}addLine(out,segment);
        }out.sort(Comparator.comparingInt((Line line)->line.top).thenComparingInt(line->line.left));return out;
    }
    private static void addLine(List<Line> out,List<Word> words){if(words.isEmpty())return;StringBuilder text=new StringBuilder();int left=Integer.MAX_VALUE,top=Integer.MAX_VALUE,right=0,bottom=0,confidence=0;boolean uncertain=false;for(Word word:words){if(text.length()>0)text.append(' ');text.append(word.text);left=Math.min(left,word.left);top=Math.min(top,word.top);right=Math.max(right,word.left+word.width);bottom=Math.max(bottom,word.top+word.height);confidence=Math.max(confidence,word.confidence);uncertain|=word.confidence<85;}out.add(new Line(text.toString(),left,top,right,bottom,confidence,uncertain));}

    /** Entry point for the Android OCR bridge. Refuses layouts without a public r/name marker. */
    static List<SocialPost> extract(List<Line> input,int screenWidth,int screenHeight){
        List<Line> lines=usable(input,screenHeight);if(privateOrComposer(lines))return Collections.emptyList();String subreddit=subreddit(lines);if(subreddit==null)return Collections.emptyList();
        if(communityFrame(lines)||scrolledCommunityFrame(lines,screenWidth))return community(lines,true,screenWidth,screenHeight);
        // Details need the visible r/name plus a u/author, age, external-domain and
        // first vote bar. That combination excludes chat, compose and comment-only UI.
        return publicDetail(lines,subreddit,screenWidth,screenHeight);
    }

    /** Community compact cards require r/name plus a recognized community sorting control. */
    static List<SocialPost> community(List<Line> input,int screenBottom){return community(input,false,1080,screenBottom);}
    /** knownPublicCommunity is supplied only by the Android state tracker after a framed r/name feed. */
    static List<SocialPost> community(List<Line> input,boolean knownPublicCommunity,int screenWidth,int screenBottom){
        List<Line> lines=usable(input,screenBottom);if(privateOrComposer(lines))return Collections.emptyList();
        if(!knownPublicCommunity&&!communityFrame(lines)&&!scrolledCommunityFrame(lines,screenWidth))return Collections.emptyList();
        List<Integer> authors=new ArrayList<>();for(int i=0;i<lines.size();i++)if(authorRow(lines.get(i)))authors.add(i);
        List<SocialPost> posts=new ArrayList<>();
        for(int index=0;index<authors.size();index++){
            int author=authors.get(index),next=index+1<authors.size()?authors.get(index+1):lines.size();EngagementRow vote=engagementRow(lines,author+1,next,screenWidth);
            // A visible card must end at its engagement row. This avoids treating a
            // cropped card or an adjacent comment as a complete claim.
            if(vote==null)continue;SocialPost post=post(lines,author+1,vote.start,0,screenWidth,lines.get(author).top,vote.bottom);if(post!=null)posts.add(post);
        }return posts;
    }

    /** Public opened-post mode is explicit and still requires subreddit + Comments boundary. */
    static List<SocialPost> publicDetail(List<Line> input,String subreddit,int screenBottom){return publicDetail(input,subreddit,1080,screenBottom);}
    /** Explicit public-detail context is required because this screen's r/name may be image-only OCR. */
    static List<SocialPost> publicDetail(List<Line> input,String subreddit,int screenWidth,int screenBottom){
        List<Line> lines=usable(input,screenBottom);if(!detailFrame(lines,subreddit))return Collections.emptyList();
        if(privateOrComposer(lines))return Collections.emptyList();
        for(int author=0;author<lines.size();author++)if(detailAuthor(lines.get(author))){
            EngagementRow vote=engagementRow(lines,author+1,lines.size(),screenWidth);
            if(vote!=null){SocialPost post=post(lines,author+1,vote.start,0,screenWidth,lines.get(author).top,vote.bottom);return post==null?Collections.emptyList():Collections.singletonList(post);}
        }return Collections.emptyList();
    }

    private static List<Line> usable(List<Line> input,int screenBottom){List<Line> out=new ArrayList<>();if(input!=null)for(Line line:input)if(line!=null&&!line.text.isEmpty()&&line.top>=0&&line.bottom<=screenBottom)out.add(line);out.sort(Comparator.comparingInt((Line line)->line.top).thenComparingInt(line->line.left));return out;}
    private static String subreddit(List<Line> lines){Pattern marker=Pattern.compile("(?i)^r/([a-z0-9_]{2,21})(?:\\s*[>›»]|\\s+[^a-z0-9_].*)?$");for(Line line:lines){Matcher m=marker.matcher(line.text);if(m.matches())return "r/"+m.group(1);}return null;}
    private static boolean communityFrame(List<Line> lines){return subreddit(lines)!=null&&hasNav(lines);}
    private static boolean scrolledCommunityFrame(List<Line> lines,int screenWidth){
        if(subreddit(lines)==null)return false;
        for(int author=0;author<lines.size();author++)if(authorRow(lines.get(author))&&engagementRow(lines,author+1,lines.size(),screenWidth)!=null)return true;
        return false;
    }
    private static boolean hasNav(List<Line> lines){for(Line line:lines)if(NAV.contains(line.text.toUpperCase(Locale.ROOT))||line.text.matches("(?i)^.{0,4}TOP\\s+POSTS(?:\\s+ALL\\s+TIME)?.{0,4}$"))return true;return false;}
    private static boolean detailFrame(List<Line> lines,String subreddit){return subreddit!=null&&SUBREDDIT.matcher(subreddit.trim()).matches();/* caller's explicit tracked public r/name permits image-only marker OCR */}
    private static boolean authorRow(Line line){String text=line.text;if(!AGE.matcher(text).find()||!DOMAIN.matcher(text).find()||SUBREDDIT.matcher(text).matches()||NAV.contains(text.toUpperCase(Locale.ROOT)))return false;return text.split("\\s+").length>=2&&!COMMENTS.matcher(text).matches();}
    /** Detail can accept a self-post's u/author + age row; feed cards require a source domain. */
    private static boolean detailAuthor(Line line){String text=line.text.toLowerCase(Locale.ROOT);return text.startsWith("u/")&&AGE.matcher(line.text).find()&&!COMMENTS.matcher(line.text).matches();}

    /**
     * Reddit's toolbar is commonly split into several ML Kit lines because its
     * icon/count groups are far apart. Recognize the complete horizontal band,
     * rather than treating any single headline number as the end of a post.
     */
    private static EngagementRow engagementRow(List<Line> lines,int from,int until,int screenWidth){
        int alignment=24,minSeparation=Math.max(80,screenWidth/10);
        for(int anchor=from;anchor<until;anchor++){
            Segment anchorSegment=segment(lines.get(anchor));if(anchorSegment.counts==0||anchorSegment.words)continue;
            int anchorCenter=centerY(lines.get(anchor)),start=anchor,bottom=lines.get(anchor).bottom;
            int segments=0,totalCounts=0,minCenter=Integer.MAX_VALUE,maxCenter=Integer.MIN_VALUE;boolean prose=false,explicitIcon=false,scaled=false;
            for(int i=from;i<until;i++){
                Line line=lines.get(i);if(Math.abs(centerY(line)-anchorCenter)>alignment)continue;
                Segment part=segment(line);if(part.words){prose=true;continue;}
                if(part.counts==0)continue;
                start=Math.min(start,i);bottom=Math.max(bottom,line.bottom);segments++;totalCounts+=part.counts;
                int center=(line.left+line.right)/2;minCenter=Math.min(minCenter,center);maxCenter=Math.max(maxCenter,center);
                explicitIcon|=part.icon;scaled|=part.scaled;
            }
            boolean joinedCounts=segments==1&&totalCounts>=2&&explicitIcon;
            boolean spreadCounts=segments>=2&&totalCounts>=2&&maxCenter-minCenter>=minSeparation;
            // Cropped detail captures sometimes retain only one explicit vote icon
            // and its abbreviated count. Keep that narrow, non-prose fallback.
            boolean croppedExplicit=segments==1&&totalCounts==1&&explicitIcon&&scaled;
            if(!prose&&(joinedCounts||spreadCounts||croppedExplicit))return new EngagementRow(start,bottom);
        }
        return null;
    }
    private static int centerY(Line line){return line.top+(line.bottom-line.top)/2;}
    private static Segment segment(Line line){
        int counts=0;boolean icon=false,words=false,scaled=false;
        for(String token:line.text.trim().split("\\s+")){
            String bare=token.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$","");
            if(bare.isEmpty()){icon=true;continue;}
            if(COUNT.matcher(bare).matches()){counts++;scaled|=SCALED_COUNT.matcher(bare).matches();continue;}
            if(bare.matches("(?i)upvotes?|comments?")||bare.matches("(?i)o")){icon=true;continue;}
            words=true;
        }
        return new Segment(counts,icon,words,scaled);
    }
    private static boolean privateOrComposer(List<Line> lines){for(Line line:lines){String text=line.text.trim().toLowerCase(Locale.ROOT);if(text.equals("chats")||text.equals("messages")||text.equals("create post")||text.equals("new post")||text.equals("reply")||text.contains("type a message")||text.contains("send a message")||text.startsWith("post title"))return true;}return false;}
    private static SocialPost post(List<Line> lines,int from,int until,int cardLeft,int cardRight,int top,int bottom){
        if(from>=until)return null;StringBuilder text=new StringBuilder();int textTop=Integer.MAX_VALUE,textBottom=0;boolean uncertain=false;
        for(int i=from;i<until;i++){Line line=lines.get(i);if(skip(line)||thumbnailColumn(line,cardRight))continue;if(text.length()>0)text.append('\n');text.append(line.text);textTop=Math.min(textTop,line.top);textBottom=Math.max(textBottom,line.bottom);uncertain|=line.uncertain;}
        String claim=text.toString();if(claim.length()<20||claim.split("\\s+").length<3)return null;return new SocialPost(SocialExtractor.REDDIT,claim,cardLeft,top,cardRight,bottom,uncertain,textTop,textBottom);
    }
    private static boolean skip(Line line){String text=line.text.toLowerCase(Locale.ROOT);return COMMENTS.matcher(line.text).matches()||text.startsWith("u/")||text.startsWith("@")||NAV.contains(line.text.toUpperCase(Locale.ROOT));}
    // Reddit link cards put publisher/logo OCR at the right; title/body begin in the main left column.
    private static boolean thumbnailColumn(Line line,int screenWidth){return screenWidth>0&&line.left*100>=screenWidth*62;}
    private static final class EngagementRow {final int start,bottom;EngagementRow(int start,int bottom){this.start=start;this.bottom=bottom;}}
    private static final class Segment {final int counts;final boolean icon,words,scaled;Segment(int counts,boolean icon,boolean words,boolean scaled){this.counts=counts;this.icon=icon;this.words=words;this.scaled=scaled;}}
    private static final class Word {final String text;final int left,top,width,height,confidence;Word(String text,int left,int top,int width,int height,int confidence){this.text=text;this.left=left;this.top=top;this.width=width;this.height=height;this.confidence=confidence;}}
}

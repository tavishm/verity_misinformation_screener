package org.fairc.forwardcheck.social;

import java.util.*;
import java.util.regex.Pattern;

/** Public-feed text only. Unknown layouts are skipped, not uploaded as a whole screen. */
public final class SocialExtractor {
    public static final String X="com.twitter.android", REDDIT="com.reddit.frontpage", NEWS="com.google.android.apps.magazines";
    public static final int MAX_TREE_DEPTH=64;
    public interface Node {
        String id(); String text(); String description(); boolean visible(); boolean editable();
        int left(); int top(); int right(); int bottom(); List<? extends Node> children();
        default List<String> urls(){return Collections.emptyList();}
    }
    private static final Pattern PRIVATE=Pattern.compile("(?i)(?:^|[_/])(?:dm_|direct_message|message_thread|chat_thread|conversation_root|inbox_message|compose_tweet|post_composer|search_edit|search_input)");
    private static final Pattern CHROME=Pattern.compile("(?i)^(?:home|popular|latest|for you|following|search|reply|share|repost|like|comment|upvote|downvote|save|more|follow|promoted|advertisement|see more|read more|\\d+[,.]?\\d*\\s*[kmb]?\\s*(?:views|likes|comments|shares|votes|reposts)?|\\d+\\s*[smhdw])$");
    // Reddit's Compose feed merges title/body into one screen-reader label.
    // Match only its observed metadata envelope, inside a known public card.
    private static final Pattern REDDIT_LABEL=Pattern.compile("(?is)^From [^,\\n]{1,80}, Posted (?:\\d+ (?:seconds?|minutes?|hours?|days?|weeks?|months?|years?) ago|just now|yesterday|today), (.+), [\\d,.]+[kmb]? upvotes?, [\\d,.]+[kmb]? comments?(?:, Reposted [\\d,.]+[kmb]? times?)?(?:, [\\d,.]+(?:[kmb]| (?:thousand|million|billion))? views?)?$");
    public static boolean supported(String app) { return X.equals(app)||REDDIT.equals(app)||NEWS.equals(app); }
    public static boolean safePublicScreen(Node root){return root!=null&&!blocked(root,0,new int[]{0});}
    public static List<SocialPost> extract(String app,Node root,int screenHeight) {
        if(!supported(app)||root==null||blocked(root,0,new int[]{0})) return Collections.emptyList();
        List<SocialPost> found=X.equals(app)?new ArrayList<>(SocialX.extract(root,screenHeight)):new ArrayList<>();
        if(found.isEmpty())walk(app,root,found,0,new int[]{0},false);
        LinkedHashMap<String,SocialPost> unique=new LinkedHashMap<>();
        int bottom=NEWS.equals(app)?newsBottom(root,screenHeight,0):screenHeight;
        for(SocialPost p:found) if(p.bottom>0&&p.top<bottom&&p.right>p.left){
            // A thin peek at the next horizontal-carousel item is not a read
            // headline. Keep badges above Google News's fixed bottom tabs.
            if(NEWS.equals(app)&&p.right-p.left<(root.right()-root.left())*.5)continue;
            if(NEWS.equals(app)&&p.textBottom>bottom)continue; // Headline still behind the bottom tabs.
            SocialPost visible=p.bottom>bottom?new SocialPost(p.app,p.text,p.left,p.top,p.right,bottom,p.media,p.completeVisibleText,p.textTop,p.textBottom,p.links):p;
            unique.putIfAbsent(p.key+":"+p.left+":"+p.top+":"+p.right+":"+p.bottom,visible);
        }
        return new ArrayList<>(unique.values());
    }
    public static SocialPost choose(List<SocialPost> posts,int height) {
        List<SocialPost> visible=readingTargets(posts,height,1);return visible.isEmpty()?null:visible.get(0);
    }
    public static List<SocialPost> readingTargets(List<SocialPost> posts,int height,int limit){
        List<SocialPost> visiblePosts=new ArrayList<>();
        for(SocialPost p:posts) {
            int visible=Math.min(height,p.bottom)-Math.max(0,p.top);
            if(visible<36 || visible < Math.min(p.bottom-p.top,120)*.65) continue;
            visiblePosts.add(p);
        }
        visiblePosts.sort((a,b)->Double.compare(readingScore(b,height),readingScore(a,height)));
        return new ArrayList<>(visiblePosts.subList(0,Math.min(Math.max(0,limit),visiblePosts.size())));
    }
    private static double readingScore(SocialPost p,int height){return Math.min(height,p.bottom)-Math.max(0,p.top)-Math.abs((p.top+p.bottom)/2.0-height*.46)*.7;}
    private static int newsBottom(Node n,int bottom,int depth){
        if(!n.visible()||depth>MAX_TREE_DEPTH)return bottom;
        if(localId(normalizedId(n.id())).equals("nav_bar_container")&&n.top()>0)return Math.min(bottom,n.top());
        for(Node c:n.children())bottom=newsBottom(c,bottom,depth+1);
        return bottom;
    }
    private static boolean blocked(Node n,int depth,int[] count) {
        if(n==null||!n.visible()) return false;
        if(depth>MAX_TREE_DEPTH||++count[0]>750) return true;
        if(PRIVATE.matcher(n.id()).find()) return true;
        // A visible composing/search field means this is not a passive feed.
        if(n.editable()) return true;
        for(Node c:n.children()) if(blocked(c,depth+1,count)) return true;
        return false;
    }
    private static void walk(String app,Node n,List<SocialPost> out,int depth,int[] count,boolean newsCompose) {
        if(n==null||!n.visible()||depth>MAX_TREE_DEPTH||++count[0]>750) return;
        String id=normalizedId(n.id());
        newsCompose=newsCompose||(NEWS.equals(app)&&localId(id).equals("compose_view"));
        if(newsCompose){
            Node headline=newsHeadline(n);
            if(headline!=null){
                // This checks the visible headline, not the article or photo.
                String text=clean(headline.text());
                out.add(new SocialPost(app,text,n.left(),n.top(),n.right(),n.bottom(),truncated(text),headline.top(),headline.bottom(),links(n,0)));
                return;
            }
        }
        if(REDDIT.equals(app)&&localId(id).equals("promoted_post_unit"))return;
        if(REDDIT.equals(app)&&localId(id).equals("post_unit")){
            String text=redditLabel(n);
            if(text!=null&&valid(text)){
                boolean incomplete=truncated(text)||redditIncomplete(n,0);
                // A link thumbnail does not truncate its headline. Keep visual
                // provenance separate so sourced text can be checked while a
                // claim about the picture still needs the image research path.
                out.add(new SocialPost(app,text,n.left(),n.top(),n.right(),n.bottom(),incomplete||redditPreview(n,0),!incomplete,-1,-1,links(n,0)));
            }
            // Unknown merged formats must not fall back to unrelated child text.
            return;
        }
        boolean container=X.equals(app)?matches(id,"tweet_row","tweet_cell","tweet_container","tweet_layout","timeline_tweet"):
                REDDIT.equals(app)?matches(id,"post_container","post_card","post_content","link_card","feed_post"):
                matches(id,"article_card","story_card","news_card","headline_card","article_item");
        if(container) {
            List<String> lines=new ArrayList<>();boolean[] media={false};gather(app,n,lines,media,0);
            String text=String.join("\n",lines);
            if(valid(text)){out.add(new SocialPost(app,text,n.left(),n.top(),n.right(),n.bottom(),media[0],-1,-1,links(n,0)));return;}
        }
        if(body(app,id)) {
            String text=clean(n.text().isEmpty()?n.description():n.text());
            if(valid(text)) out.add(new SocialPost(app,text,n.left(),n.top(),n.right(),n.bottom(),truncated(text),-1,-1,links(n,0)));
        }
        for(Node c:n.children())walk(app,c,out,depth+1,count,newsCompose);
    }
    private static Node newsHeadline(Node card){
        // The native Google News Compose card has no view IDs. Require a
        // visible headline plus the card's options control naming it; never
        // infer a post from an arbitrary publisher, timestamp or screen label.
        // Grouped stories put Full coverage outside the individual cards.
        Set<String> texts=new HashSet<>(),options=new HashSet<>();
        for(Node c:card.children())if(c.visible()){
            String text=clean(c.text());if(valid(text))texts.add(text);
            newsControls(c,options,0);
        }
        texts.retainAll(options);
        if(texts.size()!=1)return null;String title=texts.iterator().next();
        for(Node c:card.children())if(c.visible()&&clean(c.text()).equals(title))return c;
        return null;
    }
    private static void newsControls(Node n,Set<String> options,int depth){
        if(!n.visible()||depth>1)return;
        String description=clean(n.description());
        String more="More options for ";
        if(description.startsWith(more))options.add(clean(description.substring(more.length())));
        for(Node c:n.children())newsControls(c,options,depth+1);
    }
    private static void gather(String app,Node n,List<String> lines,boolean[] media,int depth) {
        if(!n.visible())return;if(depth>12){media[0]=true;return;}
        String id=normalizedId(n.id());
        if(matches(id,"avatar","author","username","screen_name","handle","subreddit","timestamp","toolbar","action_bar","vote","comment_count"))return;
        if(matches(id,"tweet_photo","media_container","video_player","preview_image","post_image","read_more","show_more"))media[0]=true;
        if(body(app,id)) {
            String value=clean(n.text().isEmpty()?n.description():n.text());
            if(truncated(value))media[0]=true;
            if(valid(value)&&!lines.contains(value))lines.add(value);
        }
        for(Node c:n.children())gather(app,c,lines,media,depth+1);
    }
    private static List<String> links(Node n,int depth){List<String> out=new ArrayList<>();if(!n.visible()||depth>12)return out;
        if(matches(normalizedId(n.id()),"avatar","author","username","screen_name","handle","profile","toolbar","action_bar"))return out;
        out.addAll(n.urls());for(Node c:n.children())out.addAll(links(c,depth+1));return out;
    }
    private static boolean body(String app,String id) {
        if(X.equals(app))return matches(id,"tweet_text","tweet_body","tweettext","quoted_tweet_text");
        if(REDDIT.equals(app))return matches(id,"post_title","link_title","post_body","selftext","post_text");
        return matches(id,"article_title","story_title","headline","article_headline");
    }
    private static String redditLabel(Node n){
        java.util.regex.Matcher own=REDDIT_LABEL.matcher(clean(n.description()));
        if(own.matches())return redditClaim(own.group(1));
        for(Node child:n.children())if(child.visible()){
            java.util.regex.Matcher m=REDDIT_LABEL.matcher(clean(child.description()));
            if(m.matches())return redditClaim(m.group(1));
        }
        return null;
    }
    private static String redditClaim(String text){return text.trim().replaceFirst("(?i)^Link domain: [a-z0-9.-]+,\\s*", "");}
    private static boolean redditPreview(Node n,int depth){
        if(!n.visible())return false;if(depth>12)return true;
        String id=normalizedId(n.id());
        // Reddit can silently cut a body preview without an ellipsis. Such a
        // card, or one with media, needs a closer check rather than a quick tick.
        if(matches(id,"post_preview_text","post_self_image","post_image","post_video","post_gallery","post_link_preview","media_container","video_player","read_more","show_more"))return true;
        for(Node c:n.children())if(redditPreview(c,depth+1))return true;
        return false;
    }
    private static boolean redditIncomplete(Node n,int depth){
        if(!n.visible())return false;if(depth>12)return true;
        if(matches(normalizedId(n.id()),"post_preview_text","read_more","show_more"))return true;
        String label=clean(n.text().isEmpty()?n.description():n.text());
        if(label.matches("(?i)^(?:read|show|see) more$"))return true;
        for(Node child:n.children())if(redditIncomplete(child,depth+1))return true;
        return false;
    }
    private static String localId(String id){return id.substring(Math.max(id.lastIndexOf('/'),id.lastIndexOf(':'))+1);}
    private static boolean matches(String id,String... tokens){for(String s:tokens)if(id.contains(s))return true;return false;}
    private static String normalizedId(String id){return id.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static boolean truncated(String text){return text.endsWith("…")||text.endsWith("...");}
    private static String clean(String s){return s.replace('\u00a0',' ').trim().replaceAll("[ \\t]+"," ");}
    private static boolean valid(String s){return s.length()>=12&&s.length()<=4000&&!CHROME.matcher(s).matches();}
}

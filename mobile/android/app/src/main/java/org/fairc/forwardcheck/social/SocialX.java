package org.fairc.forwardcheck.social;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** Strict adapter for X's observed Compose Home timeline semantics. */
public final class SocialX {
    private static final int MAX_DEPTH=64, MAX_NODES=750;
    private static final Pattern PRIVATE=Pattern.compile("(?i)(?:^|[_/])(?:dm_|direct_message|message_thread|chat_thread|conversation_root|inbox_message|compose_tweet|post_composer|search_edit|search_input)");
    private static final Pattern META=Pattern.compile("(?iu)^@\\S+$|^·?\\s*\\d+\\s*[smhdw]$|^[\\d,.]+[kmb]?$|^(?:Reply|Repost|Like|Impressions|Bookmark|Share|Show more|Read more)$");
    private static final Set<String> ACTIONS=new HashSet<>(Arrays.asList("Reply","Repost","Undo Repost","Like","Unlike","Impressions","Bookmark","Remove Bookmark","Share"));
    private static final Set<String> MEDIA=new HashSet<>(Arrays.asList("Image","Video","GIF","Media","Poll","Show results"));

    private SocialX() {}

    /** Returns visible public Home cards only. Unknown structures remain research-only via media=true. */
    public static List<SocialPost> extract(SocialExtractor.Node root,int screenHeight) {
        if(root==null||screenHeight<=0||unsafe(root,0,new int[]{0}))return Collections.emptyList();
        SocialExtractor.Node landing=find(root,"MainLanding",0,new int[]{0});
        if(landing==null)return Collections.emptyList();
        SocialExtractor.Node scaffold=find(landing,"scaffold_home_tabbed",0,new int[]{0});
        if(scaffold==null)return Collections.emptyList();
        NavState nav=new NavState(screenHeight);nav(root,0,new int[]{0},nav);
        List<SocialPost> out=new ArrayList<>();cards(scaffold,Math.min(screenHeight,nav.top),out,0,new int[]{0});
        return out;
    }

    private static boolean unsafe(SocialExtractor.Node n,int depth,int[] count){
        if(n==null||!n.visible())return false;
        if(depth>MAX_DEPTH||++count[0]>MAX_NODES)return true;
        if(n.editable()||PRIVATE.matcher(normalizedId(n.id())).find())return true;
        for(SocialExtractor.Node c:n.children())if(unsafe(c,depth+1,count))return true;
        return false;
    }

    private static SocialExtractor.Node find(SocialExtractor.Node n,String wanted,int depth,int[] count){
        if(n==null||!n.visible()||depth>MAX_DEPTH||++count[0]>MAX_NODES)return null;
        if(localId(n.id()).equals(wanted))return n;
        for(SocialExtractor.Node c:n.children()){SocialExtractor.Node found=find(c,wanted,depth+1,count);if(found!=null)return found;}
        return null;
    }

    private static void cards(SocialExtractor.Node n,int visibleBottom,List<SocialPost> out,int depth,int[] count){
        if(n==null||!n.visible()||depth>MAX_DEPTH||++count[0]>MAX_NODES)return;
        SocialExtractor.Node header=direct(n,"timeline_post");
        if(header!=null&&directDescription(n,"Post options")&&!advertisement(n,0)){
            SocialPost post=post(n,header,visibleBottom);if(post!=null)out.add(post);return;
        }
        for(SocialExtractor.Node c:n.children())cards(c,visibleBottom,out,depth+1,count);
    }

    private static SocialExtractor.Node direct(SocialExtractor.Node n,String id){for(SocialExtractor.Node c:n.children())if(c.visible()&&localId(c.id()).equals(id))return c;return null;}
    private static boolean directDescription(SocialExtractor.Node n,String description){for(SocialExtractor.Node c:n.children())if(c.visible()&&description.equals(clean(c.description())))return true;return false;}
    private static void descriptions(SocialExtractor.Node n,Set<String> out,int depth){if(!n.visible()||depth>12)return;String d=clean(n.description());if(ACTIONS.contains(d))out.add(d);for(SocialExtractor.Node c:n.children())descriptions(c,out,depth+1);}
    private static boolean advertisement(SocialExtractor.Node n,int depth){if(!n.visible()||depth>12)return false;String t=clean(n.text()),d=clean(n.description());if(t.matches("(?i)Promoted|Advertisement|Sponsored|Ad")||d.matches("(?i)Promoted|Advertisement|Sponsored|Ad"))return true;for(SocialExtractor.Node c:n.children())if(advertisement(c,depth+1))return true;return false;}

    private static SocialPost post(SocialExtractor.Node card,SocialExtractor.Node header,int visibleBottom){
        int bottom=Math.min(card.bottom(),visibleBottom);if(bottom<=0||card.top()>=visibleBottom||card.right()<=card.left())return null;
        Gather gathered=new Gather();
        for(SocialExtractor.Node child:card.children()){
            if(child==header||control(child))continue;
            boolean hasText=hasBodyText(child,0);
            if(!hasText){if(media(child,0))gathered.media=true;if(uncertainMedia(child,0))gathered.incomplete=true;continue;}
            // The observed plain body is a direct text leaf. Quotes, cards,
            // polls and future nested renderers are retained for research but
            // can never receive an automatic text-only verdict.
            if(!child.children().isEmpty()||nestedHeader(child,0)){gathered.media=true;gathered.incomplete=true;}
            if(uncertainMedia(child,0))gathered.incomplete=true;
            gather(child,gathered,0,bottom);
        }
        if(gathered.clipped)return null;
        String text=String.join("\n",gathered.lines);if(text.length()<12||text.length()>4000)return null;
        if(text.endsWith("…")||text.endsWith("...")){gathered.media=true;gathered.incomplete=true;}
        return new SocialPost(SocialExtractor.X,text,card.left(),Math.max(0,card.top()),card.right(),bottom,gathered.media,!gathered.incomplete,gathered.textTop,gathered.textBottom,new ArrayList<>(gathered.links));
    }

    private static final class Gather {final List<String> lines=new ArrayList<>();final LinkedHashSet<String> links=new LinkedHashSet<>();boolean media,clipped,incomplete;int textTop=Integer.MAX_VALUE,textBottom=-1;}
    private static void gather(SocialExtractor.Node n,Gather out,int depth,int visibleBottom){
        if(!n.visible()||depth>12)return;
        if(localId(n.id()).equals("timeline_post")||control(n))return;
        if(media(n,0))out.media=true;
        for(String url:n.urls()){String safe=https(url);if(safe!=null)out.links.add(safe);}
        String value=clean(n.text());
        if(!value.isEmpty()&&!META.matcher(value).matches()){
            if(n.top()<0||n.bottom()>visibleBottom){out.clipped=true;return;}
            if(value.matches("(?is).*\\b(?:Show more|Read more)$")){out.media=true;out.incomplete=true;}
            if(!out.lines.contains(value))out.lines.add(value);
            out.textTop=Math.min(out.textTop,n.top());out.textBottom=Math.max(out.textBottom,n.bottom());
        }
        for(SocialExtractor.Node c:n.children())gather(c,out,depth+1,visibleBottom);
    }
    private static boolean hasBodyText(SocialExtractor.Node n,int depth){if(!n.visible()||depth>12||localId(n.id()).equals("timeline_post")||control(n))return false;String t=clean(n.text());if(!t.isEmpty()&&!META.matcher(t).matches())return true;for(SocialExtractor.Node c:n.children())if(hasBodyText(c,depth+1))return true;return false;}
    private static boolean nestedHeader(SocialExtractor.Node n,int depth){if(!n.visible()||depth>12)return false;if(depth>0&&localId(n.id()).equals("timeline_post"))return true;for(SocialExtractor.Node c:n.children())if(nestedHeader(c,depth+1))return true;return false;}
    private static boolean media(SocialExtractor.Node n,int depth){if(!n.visible()||depth>12)return false;String d=clean(n.description()),t=clean(n.text());if(MEDIA.contains(d)||d.startsWith("Image: ")||d.startsWith("GIF: ")||t.equalsIgnoreCase("Show more")||t.equalsIgnoreCase("Read more"))return true;for(SocialExtractor.Node c:n.children())if(media(c,depth+1))return true;return false;}
    private static boolean uncertainMedia(SocialExtractor.Node n,int depth){if(!n.visible()||depth>12)return false;String d=clean(n.description()),t=clean(n.text());if(d.equals("Poll")||d.equals("Show results")||t.equalsIgnoreCase("Show more")||t.equalsIgnoreCase("Read more"))return true;for(SocialExtractor.Node c:n.children())if(uncertainMedia(c,depth+1))return true;return false;}
    private static boolean control(SocialExtractor.Node n){String d=clean(n.description());if(ACTIONS.contains(d)||"Post options".equals(d)||"Explain this post with Grok".equals(d))return true;if(!n.children().isEmpty()){Set<String> found=new HashSet<>();descriptions(n,found,0);if(!found.isEmpty())return true;}return false;}

    private static final class NavState {int top,bestHeight=Integer.MAX_VALUE;NavState(int screenHeight){top=screenHeight;}}
    private static int nav(SocialExtractor.Node n,int depth,int[] count,NavState state){
        if(n==null||!n.visible()||depth>MAX_DEPTH||++count[0]>MAX_NODES)return 0;
        int mask=navBit(clean(n.description()));for(SocialExtractor.Node c:n.children())mask|=nav(c,depth+1,count,state);
        if(mask==15){int height=n.bottom()-n.top();if(height>0&&(height<state.bestHeight||height==state.bestHeight&&n.top()>state.top)){state.bestHeight=height;state.top=n.top();}}
        return mask;
    }
    private static int navBit(String d){if("Home".equals(d))return 1;if("Explore".equals(d))return 2;if("Notifications tab".equals(d))return 4;if("Messages".equals(d))return 8;return 0;}

    private static String https(String raw){try{String value=raw.trim().replaceFirst("[).,;]+$","");URI u=new URI(value);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return null;return value;}catch(Exception bad){return null;}}
    private static String normalizedId(String id){return id==null?"":id.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);}
    private static String localId(String id){String value=id==null?"":id;return value.substring(Math.max(value.lastIndexOf('/'),value.lastIndexOf(':'))+1);}
    private static String clean(String value){return value==null?"":value.replace('\u00a0',' ').trim().replaceAll("[ \\t]+"," ");}
}

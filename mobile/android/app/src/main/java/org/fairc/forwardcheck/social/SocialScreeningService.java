package org.fairc.forwardcheck.social;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.util.*;

/** Independent badges for visible public cards; bounded concurrent API jobs. */
public final class SocialScreeningService extends AccessibilityService {
    private static boolean connected;
    public static boolean connected(){return connected;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SocialFeed feed=new SocialFeed();
    private final Map<SocialFeed.Reading,Card> cards=new LinkedHashMap<>();
    private final LinkedHashMap<String,CompletedDetail> completedDetails=new LinkedHashMap<>();
    private final LinkedHashMap<String,PendingDetail> pendingDetails=new LinkedHashMap<>();
    private final ReportRoundTrip reportRoundTrip=new ReportRoundTrip();
    private SocialScreenReader screenReader;private long ocrEpoch,ocrAppliedEpoch=-1,ocrStarted;private boolean ocrBusy;private int ocrWindow=-1;private List<SocialPost> ocrPosts=Collections.emptyList();
    private SocialPrefs prefs;private SocialClient client;private SocialUi ui;private WindowManager wm;private boolean live;
    private static final class Card {
        SocialFeed.Reading reading;SocialDwell.Ticket ticket;
        LinearLayout badge;TextView mark;WindowManager.LayoutParams layout;long version;boolean detailQueued,detailBusy;CompletedDetail completed;PendingDetail pending;
        String badgeSymbol,badgeText;int badgeColor;Runnable badgeAction;boolean hasBadgeState;
        Card(SocialFeed.Reading r,SocialDwell.Ticket t){reading=r;ticket=t;}
    }
    private static final class CompletedDetail {
        final SocialPost post;final SocialVerdict verdict;final SocialResearchInput input;final int window;long missingSince;int anchorLeft,anchorTop;
        CompletedDetail(SocialPost p,SocialVerdict v,SocialResearchInput i,int w){post=p;verdict=v;input=i;window=w;anchorLeft=p.left;anchorTop=p.top;}
        boolean accepts(SocialPost candidate){return sameText(post,candidate)&&Math.abs(anchorLeft-candidate.left)<=160&&Math.abs(anchorTop-candidate.top)<=720;}
        void adopt(SocialPost candidate){anchorLeft=candidate.left;anchorTop=candidate.top;}
        boolean matches(SocialPost candidate){if(!accepts(candidate))return false;adopt(candidate);return true;}
    }
    private static final class PendingDetail {
        final SocialPost post;SocialResearchInput input;final int window;long missingSince;int anchorLeft,anchorTop;
        PendingDetail(SocialPost p,SocialResearchInput i,int w){post=p;input=i;window=w;anchorLeft=p.left;anchorTop=p.top;}
        boolean accepts(SocialPost candidate){return sameText(post,candidate)&&Math.abs(anchorLeft-candidate.left)<=160&&Math.abs(anchorTop-candidate.top)<=720;}
        void adopt(SocialPost candidate){anchorLeft=candidate.left;anchorTop=candidate.top;}
        boolean matches(SocialPost candidate){if(!accepts(candidate))return false;adopt(candidate);return true;}
    }
    static final class ReportRoundTrip {
        static final long LAUNCH_GRACE_MS=3000;
        boolean active,seenReport;String app="";int window=-1;long launchUntil;
        void begin(String sourceApp,int sourceWindow,long now){active=true;seenReport=false;app=sourceApp;window=sourceWindow;launchUntil=now+LAUNCH_GRACE_MS;}
        boolean suspend(long now,boolean reportForeground){if(!active)return false;if(reportForeground)seenReport=true;return reportForeground||(!seenReport&&now<=launchUntil);}
        boolean waitingToLaunch(long now){return active&&!seenReport&&now<=launchUntil;}
        boolean returnedTo(String candidateApp,int candidateWindow){if(!active||!seenReport)return false;boolean same=app.equals(candidateApp)&&window==candidateWindow;clear();return same;}
        void expire(long now){if(active&&!seenReport&&now>launchUntil)clear();}
        void clear(){active=false;seenReport=false;app="";window=-1;launchUntil=0;}
    }
    static String normalizedText(String text){return text==null?"":text.trim().replaceAll("\\s+"," ");}
    static boolean sameClaimText(String first,String second){return normalizedText(first).equals(normalizedText(second));}
    private static boolean sameText(SocialPost first,SocialPost second){return first!=null&&second!=null&&first.app.equals(second.app)&&sameClaimText(first.text,second.text);}
    private String lastDiagnostic="";
    private void diagnostic(String message){if(android.util.Log.isLoggable("ForwardCheckSocial",android.util.Log.DEBUG)&&!message.equals(lastDiagnostic)){lastDiagnostic=message;android.util.Log.d("ForwardCheckSocial",message);}}
    private final Runnable tick=()->scan(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,k)->configure();
    @Override protected void onServiceConnected(){live=true;connected=true;prefs=new SocialPrefs(this);client=SocialClient.get(this);screenReader=new SocialScreenReader(this);ui=new SocialUi(this);wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        getSharedPreferences(SocialPrefs.FILE,MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changed);configure();}
    private void configure(){if(prefs==null)return;main.removeCallbacks(tick);reportRoundTrip.clear();clearDetailState();reset();invalidateOcr();AccessibilityServiceInfo info=getServiceInfo();
        info.packageNames=prefs.enabled()?prefs.apps().toArray(new String[0]):new String[]{"org.fairc.screening.disabled"};setServiceInfo(info);
        if(prefs.enabled()){client.warm();main.post(tick);}}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(prefs==null||!prefs.enabled()||!prefs.allows(String.valueOf(event.getPackageName())))return;
        // Animated adverts produce content-change events continuously. Keep
        // the reading identity while a fresh OCR frame is in flight; actual
        // navigation/scroll/taps clear it immediately.
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_SCROLLED)invalidateOcr();
        else if(event.getEventType()!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED){invalidateOcr();reset();}
        main.removeCallbacks(tick);main.postDelayed(tick,80);}
    private void reconcile(){
        long now=SystemClock.elapsedRealtime();List<SocialPost> visible=visiblePosts();feed.observe(visible,now);if(!reportRoundTrip.suspend(now,SocialResultActivity.reportForeground()))updateDetailVisibility(visible,now);
        List<Map.Entry<SocialFeed.Reading,Card>> moved=new ArrayList<>();Iterator<Map.Entry<SocialFeed.Reading,Card>> it=cards.entrySet().iterator();
        while(it.hasNext()){Map.Entry<SocialFeed.Reading,Card> entry=it.next();Card card=entry.getValue();if(feed.accepts(entry.getKey(),card.ticket)){if(card.badge==null&&card.hasBadgeState)restoreBadge(card);else if(!updateBadge(card))hide(card);continue;}
            SocialFeed.Reading replacement=detailReplacement(card);if(replacement!=null){SocialDwell.Ticket ticket=replacement.begin(now+SocialDwell.PAUSE_MS);if(ticket!=null){it.remove();card.reading=replacement;card.ticket=ticket;if(updateBadge(card))moved.add(new AbstractMap.SimpleImmutableEntry<>(replacement,card));else hide(card);continue;}}
            hide(card);it.remove();
        }
        for(Map.Entry<SocialFeed.Reading,Card> entry:moved)cards.put(entry.getKey(),entry.getValue());
    }
    private void updateDetailVisibility(List<SocialPost> visible,long now){
        Iterator<Map.Entry<String,CompletedDetail>> complete=completedDetails.entrySet().iterator();while(complete.hasNext()){CompletedDetail state=complete.next().getValue();boolean seen=false;for(SocialPost post:visible)if(state.matches(post)){seen=true;break;}if(seen)state.missingSince=0;else if(state.missingSince==0)state.missingSince=now;else if(now-state.missingSince>2000)complete.remove();}
        Iterator<Map.Entry<String,PendingDetail>> pending=pendingDetails.entrySet().iterator();while(pending.hasNext()){PendingDetail state=pending.next().getValue();boolean seen=false;for(SocialPost post:visible)if(state.matches(post)){seen=true;break;}if(seen)state.missingSince=0;else if(state.missingSince==0)state.missingSince=now;else if(now-state.missingSince>2000)pending.remove();}
    }
    private SocialFeed.Reading detailReplacement(Card card){
        CompletedDetail complete=card.completed!=null&&activeWindow(card.completed.post.app)==card.completed.window?card.completed:completed(card.reading.post());PendingDetail pending=card.pending!=null&&activeWindow(card.pending.post.app)==card.pending.window?card.pending:pending(card.reading.post());if(complete==null&&pending==null)return null;
        SocialFeed.Reading best=null;int distance=Integer.MAX_VALUE;int anchor=complete!=null?complete.anchorTop:pending.anchorTop;
        for(SocialFeed.Reading reading:feed.readings()){Card owner=cards.get(reading);if(owner!=null&&owner!=card)continue;SocialPost post=reading.post();boolean match=complete!=null?complete.accepts(post):pending.accepts(post);if(match){int d=Math.abs(post.top-anchor);if(d<distance){best=reading;distance=d;}}}
        if(best!=null){if(complete!=null)complete.adopt(best.post());else pending.adopt(best.post());}return best;
    }
    private void scan(boolean schedule){
        if(!live||prefs==null||!prefs.enabled()){reset();return;}
        reconcile();diagnostic("Visible cards: "+feed.readings().size());
        // Compose may replace a card object while leaving the same post in the
        // same viewport. Reattach an explicit result before any quick result.
        for(Card card:new ArrayList<>(cards.values())){
            CompletedDetail completed=completed(card.reading.post());
            if(completed!=null&&card.completed!=completed){card.version++;card.detailQueued=false;card.detailBusy=false;card.completed=completed;renderDetail(card,completed);}
            else if(completed==null){PendingDetail pending=pending(card.reading.post());if(pending!=null&&card.pending!=pending){card.version++;card.detailQueued=false;card.detailBusy=true;card.pending=pending;show(card,"·","Checking sources…",SocialUi.INK,null);}}
        }
        // Explicit detailed checks get the next available slot. Automatic cards
        // wait quietly instead of being dropped when the API slots are busy.
        for(Card c:new ArrayList<>(cards.values()))if(c.detailQueued&&client.hasCapacity())startDetail(c);
        for(SocialFeed.Reading reading:feed.readings()){
            if(cards.containsKey(reading))continue;
            SocialPost post=reading.post();CompletedDetail completed=completed(post);PendingDetail pending=completed==null?pending(post):null;SocialVerdict cached=completed==null&&pending==null?client.cached(post):null;
            if(completed==null&&pending==null&&cached==null&&!client.hasCapacity())continue;
            long now=SystemClock.elapsedRealtime();SocialDwell.Ticket ticket=reading.begin(now+(completed!=null||pending!=null?SocialDwell.PAUSE_MS:0));if(ticket==null)continue;
            Card card=new Card(reading,ticket);cards.put(reading,card);long version=card.version;
            if(completed!=null){card.completed=completed;card.version++;renderDetail(card,completed);}
            else if(pending!=null){card.pending=pending;card.version++;card.detailBusy=true;show(card,"·","Checking sources…",SocialUi.INK,null);}
            else if(cached!=null)render(card,cached);else client.check(post,false,result->{if(version==card.version)render(card,result);});
        }
        if(schedule){main.removeCallbacks(tick);main.postDelayed(tick,200);}
    }
    private boolean stillHere(Card card){
        if(!live||prefs==null||!prefs.enabled())return false;
        reconcile();return cards.get(card.reading)==card&&feed.accepts(card.reading,card.ticket);
    }
    private void render(Card card,SocialVerdict verdict){
        if(!stillHere(card))return;diagnostic("Render "+verdict.kind+" / "+verdict.basis);
        CompletedDetail completed=completed(card.reading.post());if(completed!=null){if(card.completed!=completed){card.completed=completed;renderDetail(card,completed);}return;}
        PendingDetail pending=pending(card.reading.post());if(pending!=null){if(card.pending!=pending){card.pending=pending;show(card,"·","Checking sources…",SocialUi.INK,null);}return;}
        if("skip".equals(verdict.kind)){card.hasBadgeState=false;hide(card);return;}
        SocialPost post=card.reading.post();
        if("checking".equals(verdict.kind)){show(card,"·","Checking…",SocialUi.INK,null);return;}
        if(verdict.decisive()){
            String origin="visible_text".equals(verdict.basis)?"Visible text · ":"quick_ai".equals(verdict.basis)?"AI · ":"news_excerpt".equals(verdict.basis)||"news_context".equals(verdict.basis)?verdict.sourceLabel()+" · ":"Text · ";
            show(card,"true".equals(verdict.kind)?"✓":"×",origin+verdict.label(),"true".equals(verdict.kind)?SocialUi.GREEN:SocialUi.RED,()->{
                if(stillHere(card))researchInput(card,input->{if(stillHere(card))startActivity(SocialResultActivity.intent(this,post,verdict,input).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));});
            });
            if(card.badge!=null){card.mark.setScaleX(.4f);card.mark.setScaleY(.4f);card.mark.animate().scaleX(1).scaleY(1).setDuration(240).start();}
        }else show(card,"?",verdict.note.contains("limit")?"Monthly limit reached":"Unsure · Research deeper",SocialUi.AMBER,()->detail(card));
    }
    private void renderDetail(Card card,CompletedDetail completed){
        if(!stillHere(card)||completed(card.reading.post())!=completed)return;
        SocialVerdict verdict=completed.verdict;String symbol,colorText;int color;
        if(verdict.decisive()){symbol="true".equals(verdict.kind)?"✓":"×";colorText=("visible_text".equals(verdict.basis)?"Visible text":verdict.sourceLabel())+" · "+verdict.label();color="true".equals(verdict.kind)?SocialUi.GREEN:SocialUi.RED;}
        else if("skip".equals(verdict.kind)){symbol="·";colorText="No factual claim · See why";color=SocialUi.INK;}
        else{symbol="?";colorText="Still unsure · See why";color=SocialUi.AMBER;}
        show(card,symbol,colorText,color,()->{if(stillHere(card)&&completed(card.reading.post())==completed&&activeWindow(completed.post.app)==completed.window){reportRoundTrip.begin(completed.post.app,completed.window,SystemClock.elapsedRealtime());startActivity(SocialResultActivity.intent(this,completed.post,verdict,completed.input).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}});
    }
    private void detail(Card card){
        if(card.detailBusy||card.detailQueued||!stillHere(card))return;
        card.detailQueued=true;card.version++;
        show(card,"·",client.hasCapacity()?"Checking sources…":"Waiting to check…",SocialUi.INK,null);
        if(client.hasCapacity())startDetail(card);
    }
    private void startDetail(Card card){
        if(!stillHere(card))return;card.detailQueued=false;card.detailBusy=true;
        long version=++card.version;SocialPost post=card.reading.post();int window=activeWindow(post.app);if(window<0)return;
        PendingDetail pending=new PendingDetail(post,new SocialResearchInput(post.text,"",SocialClient.links(post)),window);card.pending=pending;pendingDetails.put(detailKey(post),pending);
        show(card,"·","Checking sources…",SocialUi.INK,null);
        researchInput(card,input->{if(version!=card.version||!stillHere(card)||pendingDetails.get(detailKey(post))!=pending||activeWindow(post.app)!=window)return;pending.input=input;client.check(post,true,input,result->{
            if(pendingDetails.get(detailKey(post))!=pending||activeWindow(post.app)!=window)return;pendingDetails.remove(detailKey(post));card.detailBusy=false;
            CompletedDetail completed=new CompletedDetail(post,result,input,window);completedDetails.put(detailKey(post),completed);while(completedDetails.size()>4)completedDetails.remove(completedDetails.keySet().iterator().next());
            scan(false);
        });});
    }
    private static String detailKey(SocialPost post){return post==null?"":SocialPost.digest(post.app+"\n"+normalizedText(post.text));}
    private CompletedDetail completed(SocialPost post){CompletedDetail value=post==null?null:completedDetails.get(detailKey(post));return value!=null&&activeWindow(post.app)==value.window&&value.matches(post)?value:null;}
    private PendingDetail pending(SocialPost post){PendingDetail value=post==null?null:pendingDetails.get(detailKey(post));return value!=null&&activeWindow(post.app)==value.window&&value.matches(post)?value:null;}
    private interface ResearchReady {void result(SocialResearchInput input);}
    private void researchInput(Card card,ResearchReady callback){
        if(!stillHere(card))return;SocialPost post=card.reading.post();int window=activeWindow(post.app);
        if(window<0){diagnostic("Research capture: application window unavailable");callback.result(new SocialResearchInput(post.text,"",SocialClient.links(post)));return;}
        screenReader.image(window,post,jpeg->{diagnostic("Research capture: image attached="+!jpeg.isEmpty());if(stillHere(card)&&activeWindow(post.app)==window)callback.result(new SocialResearchInput(post.text,jpeg,SocialClient.links(post)));});
    }
    private int activeWindow(String app){try{for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&(w.isActive()||w.isFocused())){AccessibilityNodeInfo root=w.getRoot();if(root==null)return -1;try{return app.equals(String.valueOf(root.getPackageName()))?w.getId():-1;}finally{root.recycle();}}}catch(Exception ignored){}return -1;}
    private void invalidateOcr(){ocrEpoch++;ocrAppliedEpoch=-1;ocrPosts=Collections.emptyList();if(screenReader!=null)screenReader.invalidateHistory();}
    private List<SocialPost> fallbackOcr(int windowId,int width,int height){
        long now=SystemClock.elapsedRealtime();
        if(ocrWindow!=windowId){invalidateOcr();ocrWindow=windowId;}
        if(!ocrBusy&&(ocrAppliedEpoch!=ocrEpoch||now-ocrStarted>=1500)&&now-ocrStarted>=450){
            ocrBusy=true;ocrStarted=now;long epoch=ocrEpoch;
            screenReader.read(windowId,width,height,posts->{ocrBusy=false;if(!live||epoch!=ocrEpoch||!prefs.allows(SocialExtractor.REDDIT)||activeWindow(SocialExtractor.REDDIT)!=windowId)return;
                diagnostic("OCR cards: "+posts.size()+(posts.isEmpty()?"":"; bounds "+posts.get(0).top+","+posts.get(0).bottom+"; text "+posts.get(0).textTop+","+posts.get(0).textBottom));ocrPosts=posts;ocrAppliedEpoch=epoch;main.removeCallbacks(tick);main.post(tick);});
        }
        return ocrAppliedEpoch==ocrEpoch?ocrPosts:Collections.emptyList();
    }
    private void show(Card card,String symbol,String text,int color,Runnable action){
        card.badgeSymbol=symbol;card.badgeText=text;card.badgeColor=color;card.badgeAction=action;card.hasBadgeState=true;hide(card);SocialPost post=card.reading.post();
        LinearLayout badge=new LinearLayout(this);card.badge=badge;badge.setGravity(Gravity.CENTER_VERTICAL);boolean compact=SocialExtractor.X.equals(post.app);
        badge.setPadding(ui.dp(compact?9:13),ui.dp(compact?2:7),ui.dp(compact?10:15),ui.dp(compact?2:7));
        badge.setBackground(SocialUi.round(Color.WHITE,ui.dp(24)));badge.setElevation(ui.dp(8));badge.setMinimumHeight(ui.dp(compact?28:48));
        TextView mark=ui.text(symbol,compact?18:26);card.mark=mark;mark.setTextColor(color);mark.setPadding(0,0,ui.dp(9),0);
        TextView label=ui.text(text,compact?14:16);label.setTextColor(color);label.setMaxWidth(ui.dp(248));
        badge.addView(mark);badge.addView(label);badge.setContentDescription(text);badge.setOnClickListener(action==null?null:v->action.run());
        int h=getResources().getDisplayMetrics().heightPixels;
        int width=Math.min(getResources().getDisplayMetrics().widthPixels,post.right-post.left)-ui.dp(24);
        if(width<ui.dp(100)){card.badge=null;return;}
        badge.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int badgeTop=SocialBadgePlacement.top(post,badge.getMeasuredHeight(),h,getResources().getDisplayMetrics().density);
        if(badgeTop<0){diagnostic("Badge has no room: "+post.top+","+post.bottom+" text "+post.textTop+","+post.textBottom+" height "+badge.getMeasuredHeight());card.badge=null;return;}
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        // Anchor to this card, including narrower horizontal story cards.
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=Math.max(post.left+ui.dp(8),post.right-ui.dp(12)-badge.getMeasuredWidth());p.y=badgeTop;p.setTitle("Verity · "+text);
        card.layout=p;try{wm.addView(badge,p);}catch(Exception e){android.util.Log.w("ForwardCheckSocial","Overlay unavailable: "+e.getClass().getSimpleName());card.badge=null;card.layout=null;}
    }
    private void restoreBadge(Card card){if(card.hasBadgeState)show(card,card.badgeSymbol,card.badgeText,card.badgeColor,card.badgeAction);}
    private boolean updateBadge(Card card){
        if(card.badge==null||card.layout==null)return false;
        SocialPost post=card.reading.post();int h=getResources().getDisplayMetrics().heightPixels;
        int width=Math.min(getResources().getDisplayMetrics().widthPixels,post.right-post.left)-ui.dp(24);if(width<ui.dp(100))return false;
        card.badge.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int top=SocialBadgePlacement.top(post,card.badge.getMeasuredHeight(),h,getResources().getDisplayMetrics().density);if(top<0)return false;
        int x=Math.max(post.left+ui.dp(8),post.right-ui.dp(12)-card.badge.getMeasuredWidth());if(card.layout.x==x&&card.layout.y==top)return true;card.layout.x=x;card.layout.y=top;
        try{wm.updateViewLayout(card.badge,card.layout);return true;}catch(Exception changed){return false;}
    }
    private void hide(Card card){if(card.badge!=null){card.badge.animate().cancel();try{wm.removeView(card.badge);}catch(Exception ignored){}card.badge=null;}card.layout=null;}
    private void reset(){for(Card card:cards.values())hide(card);cards.clear();feed.clear();}
    private List<SocialPost> visiblePosts(){
        // Other apps can cover a selected feed without a selected-app event.
        try{for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!(window.isActive()||window.isFocused()))continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null)return Collections.emptyList();
            try{String app=String.valueOf(root.getPackageName());long now=SystemClock.elapsedRealtime();boolean report=SocialResultActivity.reportForeground();
                if(getPackageName().equals(app)&&reportRoundTrip.suspend(now,report)){reset();return Collections.emptyList();}
                if(!prefs.allows(app)){reportRoundTrip.clear();clearDetailState();invalidateOcr();return Collections.emptyList();}
                if(reportRoundTrip.active){if(reportRoundTrip.seenReport){if(!reportRoundTrip.returnedTo(app,window.getId()))clearDetailState();}else reportRoundTrip.expire(now);}
                retainDetailScope(app,window.getId());
                int[] budget={0,0};Snapshot tree=Snapshot.copy(root,0,budget);if(tree==null||budget[1]!=0)return Collections.emptyList();
                int height=getResources().getDisplayMetrics().heightPixels;
                List<SocialPost> extracted=SocialExtractor.extract(app,tree,height);
                if(extracted.isEmpty()&&SocialExtractor.REDDIT.equals(app)&&SocialExtractor.safePublicScreen(tree))extracted=fallbackOcr(window.getId(),getResources().getDisplayMetrics().widthPixels,height);
                return SocialExtractor.readingTargets(extracted,height,4);
            }finally{root.recycle();}
        }}catch(RuntimeException unavailable){diagnostic("Feed window changed");}
        return Collections.emptyList();
    }
    private void retainDetailScope(String app,int window){completedDetails.entrySet().removeIf(e->!app.equals(e.getValue().post.app)||e.getValue().window!=window);pendingDetails.entrySet().removeIf(e->!app.equals(e.getValue().post.app)||e.getValue().window!=window);}
    private void clearDetailState(){completedDetails.clear();pendingDetails.clear();}
    @Override public void onInterrupt(){diagnostic("Interrupted");reportRoundTrip.clear();clearDetailState();reset();}
    @Override public void onDestroy(){live=false;connected=false;main.removeCallbacksAndMessages(null);reportRoundTrip.clear();clearDetailState();reset();if(screenReader!=null)screenReader.close();if(prefs!=null)getSharedPreferences(SocialPrefs.FILE,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changed);super.onDestroy();}
    static final class Snapshot implements SocialExtractor.Node {
        String id,text,description;List<String> urls=new ArrayList<>();boolean editable;Rect bounds;List<Snapshot> children=new ArrayList<>();
        static Snapshot copy(AccessibilityNodeInfo n,int depth,int[] count){if(!n.isVisibleToUser())return null;if(depth>SocialExtractor.MAX_TREE_DEPTH||++count[0]>750){count[1]=1;return null;}
            Snapshot s=new Snapshot();s.id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName();s.text=n.getText()==null?"":n.getText().toString();s.description=n.getContentDescription()==null?"":n.getContentDescription().toString();s.editable=n.isEditable();
            if(n.getText() instanceof android.text.Spanned){android.text.Spanned span=(android.text.Spanned)n.getText();for(android.text.style.URLSpan link:span.getSpans(0,span.length(),android.text.style.URLSpan.class))s.urls.add(link.getURL());}
            s.bounds=new Rect();n.getBoundsInScreen(s.bounds);
            for(int i=0;i<n.getChildCount();i++){if(count[0]>=750){count[1]=1;break;}AccessibilityNodeInfo c=n.getChild(i);if(c!=null)try{Snapshot child=copy(c,depth+1,count);if(child!=null)s.children.add(child);}finally{c.recycle();}}return s;}
        public List<String> urls(){return urls;}public String id(){return id;}public String text(){return text;}public String description(){return description;}public boolean visible(){return true;}public boolean editable(){return editable;}
        public int left(){return bounds.left;}public int top(){return bounds.top;}public int right(){return bounds.right;}public int bottom(){return bounds.bottom;}public List<Snapshot> children(){return children;}
    }
}

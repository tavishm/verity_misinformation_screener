package org.fairc.forwardcheck;

import android.accessibilityservice.*;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Visible-chat helper: bounded scans, stable local choices, no network access. */
public final class ForwardAccessibilityService extends AccessibilityService {
    private static volatile ForwardAccessibilityService active;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService local=Executors.newSingleThreadExecutor();
    private final MessageTracker tracker=new MessageTracker();
    private final Set<String> busy=new HashSet<>();
    private final LinkedHashMap<String,Decision> decisions=new LinkedHashMap<String,Decision>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Decision> e){return size()>256;}};
    private SharedPreferences choices; private ChosenContacts contacts; private SmsSenderLookup smsContacts; private WindowManager windows;
    private View overlay; private String pendingId, pendingChat, overlayJob; private Decision pending;
    private long checkGeneration;
    private LinearLayout panel; private TextView overlayStatus;
    private final Runnable pollOverlay=this::pollResult;
    private ChatSnapshot visible; private boolean queued,destroyed,connected;
    private long scans,events; private String reason="starting",gate="none",rootPackage="none",lastSource="none";private int lastScreenedCharacters;
    private final Runnable scheduled=()->{queued=false;try{scan();}catch(RuntimeException changedWindow){reason="window_changed_during_scan";clearPrompt();}};
    private final Runnable heartbeat=new Runnable(){public void run(){if(destroyed)return;schedule(0);main.postDelayed(this,1000);}};
    private static final class Decision {
        String text,source,note=""; boolean offer,spam,noText; final boolean sms;
        Decision(String text,String source){this.text=text;this.source=source;this.sms="sms".equals(source);}
    }
    @Override protected void onServiceConnected(){
        active=this;destroyed=false;connected=true;choices=getSharedPreferences("message_sequences",MODE_PRIVATE);tracker.restore(choices.getString("state","{}"));contacts=new ChosenContacts(this);windows=getSystemService(WindowManager.class);
        if(smsContacts!=null)smsContacts.close();smsContacts=new SmsSenderLookup(this,main,local,()->schedule(0));
        AccessibilityServiceInfo info=getServiceInfo();info.eventTypes=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED|AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED|AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType=AccessibilityServiceInfo.FEEDBACK_GENERIC;info.notificationTimeout=100;info.packageNames=new String[]{"com.whatsapp","com.whatsapp.w4b",SmsPolicy.GOOGLE_MESSAGES};
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;setServiceInfo(info);
        schedule(150);main.removeCallbacks(heartbeat);main.postDelayed(heartbeat,1000);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){events++;schedule(160);}
    private void schedule(long delay){if(!destroyed && !queued){queued=true;main.postDelayed(scheduled,delay);}}
    @Override public void onInterrupt(){clearPrompt();}
    @Override public void onDestroy(){destroyed=true;connected=false;if(active==this)active=null;save();if(smsContacts!=null)smsContacts.close();main.removeCallbacksAndMessages(null);local.shutdownNow();clearPrompt();super.onDestroy();}
    private AccessibilityNodeInfo root(){
        if(getSystemService(KeyguardManager.class).isKeyguardLocked())return null;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null && allowed(String.valueOf(root.getPackageName())))return root;
        if(root!=null && overlay!=null && getPackageName().equals(String.valueOf(root.getPackageName()))) {
            root.recycle();
            for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && (w.isFocused() || w.isActive())){AccessibilityNodeInfo app=w.getRoot();if(app!=null && allowed(String.valueOf(app.getPackageName())))return app;if(app!=null)app.recycle();}
            return null;
        }
        if(root!=null)root.recycle();return null;
    }
    private void scan(){
        if(destroyed)return;scans++;AccessibilityNodeInfo root=root();
        if(root==null){rootPackage="none";visible=null;clearPrompt();reason="outside_supported_messages_or_locked";return;}
        rootPackage=String.valueOf(root.getPackageName());
        ChatSnapshot frame;try{frame=snapshot(root);}finally{root.recycle();}
        if(frame.chat.isEmpty() || frame.bubbles.isEmpty()){visible=frame;clearPrompt();reason="no_visible_bubbles";return;}
        List<MessageTracker.Item> items=new ArrayList<>();for(ChatSnapshot.Bubble b:frame.bubbles)items.add(new MessageTracker.Item(b.signature,b.hint,b.eligible,b.instance));
        List<MessageTracker.Entry> entries=tracker.reconcile(frame.chat,items,frame.sms);for(int i=0;i<entries.size();i++)frame.bubbles.get(i).entry=entries.get(i);
        visible=frame;save();
        if(overlay!=null){if(!frame.chat.equals(pendingChat) || !contains(pendingId))clearPrompt();else {reason="prompt_visible";return;}}
        for(int i=frame.bubbles.size()-1;i>=0;i--){ChatSnapshot.Bubble b=frame.bubbles.get(i);if(!b.eligible || b.entry.settled)continue;
            String id=b.entry.id;Decision known=decisions.get(id);
            if(known!=null){if(known.offer){showPrompt(id,frame.chat,known);return;}tracker.settle(id);save();continue;}
            if(busy.add(id)){reason=b.image==null?"screening_text":"reading_image";if(b.image==null)classify(id,frame.chat,b.text,b.source,"",false);else capture(frame,b);}
            return;
        }
        reason="no_new_message";
    }
    private ChatSnapshot snapshot(AccessibilityNodeInfo root){return SmsPolicy.supported(String.valueOf(root.getPackageName()))?SmsSnapshot.read(this,root,smsContacts):ChatSnapshot.read(root,contacts);}
    private boolean contains(String id){if(visible==null || id==null)return false;for(ChatSnapshot.Bubble b:visible.bubbles)if(b.eligible && b.entry!=null && id.equals(b.entry.id))return true;return false;}
    private void classify(String id,String chat,String text,String source,String note,boolean noText){
        local.execute(()->{
            Decision d=new Decision(text,source);d.note=note;d.noText=noText;
            try{if(noText)d.offer=!d.sms;else{LocalClassifier.Decision c=ScreeningClassifier.classify(this,text);d.spam=SmsPolicy.warn(c.action);d.offer=d.sms?d.spam:d.spam||"factual_offer".equals(c.action)||"uncertain_offer".equals(c.action)||"offer_link_check".equals(c.action);}}
            catch(Exception e){d.offer=!d.sms;d.note="Could not screen this message on the phone.";}
            main.post(()->{busy.remove(id);if(destroyed)return;decisions.put(id,d);lastSource=source;lastScreenedCharacters=text.length();gate=d.spam?"spam_warning":d.offer?"factual_offer":"personal_skip";if(!d.offer){tracker.settle(id);save();}schedule(0);});
        });
    }
    private void capture(ChatSnapshot frame,ChatSnapshot.Bubble bubble){
        final String id=bubble.entry.id,chat=frame.chat;Rect crop=new Rect(bubble.image),window=new Rect(frame.windowBounds);
        TakeScreenshotCallback callback=new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result){
                Bitmap hardware=null,bitmap=null;
                try{
                    hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());if(hardware==null)throw new IllegalStateException();
                    // Refuse stale crops after scrolling or switching chats.
                    AccessibilityNodeInfo current=root();if(current==null)throw new IllegalStateException();ChatSnapshot now;try{now=snapshot(current);}finally{current.recycle();}
                    boolean unchanged=false;for(ChatSnapshot.Bubble b:now.bubbles)if(b.signature.equals(bubble.signature) && b.image!=null && b.image.equals(crop))unchanged=true;
                    if(!chat.equals(now.chat) || !unchanged)throw new IllegalStateException();
                    float sx=(float)hardware.getWidth()/window.width(),sy=(float)hardware.getHeight()/window.height();
                    int x=Math.max(0,Math.round((crop.left-window.left)*sx)),y=Math.max(0,Math.round((crop.top-window.top)*sy));
                    int w=Math.min(hardware.getWidth()-x,Math.round(crop.width()*sx)),h=Math.min(hardware.getHeight()-y,Math.round(crop.height()*sy));if(w<40||h<40)throw new IllegalStateException();
                    Bitmap software=hardware.copy(Bitmap.Config.ARGB_8888,false);bitmap=Bitmap.createBitmap(software,x,y,w,h);if(bitmap!=software)software.recycle();
                    final Bitmap pixels=bitmap;
                    local.execute(()->{try{ImageScreen.Result r=ImageScreen.get(ForwardAccessibilityService.this).screen(pixels);
                        String text=(bubble.text+"\n"+r.text).trim();String note=ImageScreen.aiNote(r.aiScore);
                        main.post(()->{if(!destroyed)classify(id,chat,text.isEmpty()?"[Picture with no readable text]":text,"image",note,text.length()<3);});
                    }catch(Exception e){main.post(()->imageFailed(id,chat));}finally{pixels.recycle();}});
                }catch(Exception e){if(bitmap!=null)bitmap.recycle();imageFailed(id,chat);}
                finally{if(hardware!=null)hardware.recycle();result.getHardwareBuffer().close();}
            }
            @Override public void onFailure(int errorCode){imageFailed(id,chat);}
        };
        try{if(Build.VERSION.SDK_INT>=34)takeScreenshotOfWindow(frame.windowId,getMainExecutor(),callback);else takeScreenshot(android.view.Display.DEFAULT_DISPLAY,getMainExecutor(),callback);}catch(Exception e){imageFailed(id,chat);}
    }
    private void imageFailed(String id,String chat){busy.remove(id);if(destroyed)return;
        // A failed capture is recoverable through WhatsApp Share, never a verdict.
        Decision d=new Decision("[Picture could not be read]","image");d.offer=true;d.noText=true;d.note="Share this picture to Verity to read it.";decisions.put(id,d);reason="image_capture_unavailable";schedule(0);
    }
    private void showPrompt(String id,String chat,Decision d){
        if(overlay!=null || !contains(id))return;pending=d;pendingId=id;pendingChat=chat;
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(14),dp(18),dp(10));
        GradientDrawable bg=new GradientDrawable();bg.setColor(d.spam?0xfffff4e5:0xfff6fbf7);bg.setCornerRadius(dp(20));panel.setBackground(bg);panel.setElevation(dp(12));
        TextView title=new TextView(this);title.setText(I18n.text(this,d.spam?"This may be a scam":d.noText?"Check this picture?":"Is this message true?"));title.setTextColor(Ui.INK);title.setTextSize(21);title.setTypeface(null,Typeface.BOLD);panel.addView(title);
        TextView preview=new TextView(this);preview.setText(d.spam?I18n.text(this,"Please ignore it."):d.noText?I18n.text(this,"Tap to see what we found."):d.text);preview.setTextSize(17);preview.setTextColor(Ui.MUTED);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);preview.setPadding(0,dp(8),0,dp(4));panel.addView(preview);
        if(!d.spam && !d.noText)panel.addView(label("Only this message’s text is sent when you tap Check.",14));
        LinearLayout buttons=new LinearLayout(this);Button no=new Button(this);no.setText(I18n.text(this,d.spam?"Ignore":"Not now"));no.setAllCaps(false);no.setTextSize(18);no.setMinHeight(dp(54));no.setOnClickListener(v->{acknowledgePrompt();clearPrompt();});
        Button yes=new Button(this);yes.setText(I18n.text(this,d.spam?"Why?":"Check"));yes.setAllCaps(false);yes.setTextSize(18);yes.setMinHeight(dp(54));yes.setOnClickListener(v->openConsent());buttons.addView(no,new LinearLayout.LayoutParams(0,-2,1));buttons.addView(yes,new LinearLayout.LayoutParams(0,-2,1));panel.addView(buttons);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(Math.min(dp(360),getResources().getDisplayMetrics().widthPixels-dp(24)),-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(54);
        try{windows.addView(panel,p);overlay=panel;reason="prompt_visible";}catch(Exception e){overlay=null;reason="overlay_unavailable";}
    }
    private TextView label(String text,int size){return rawLabel(I18n.text(this,text),size);}
    private TextView rawLabel(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(Ui.INK);v.setPadding(0,dp(6),0,dp(6));return v;}
    private Button action(String text,Runnable run){Button b=new Button(this);b.setText(I18n.text(this,text));b.setAllCaps(false);b.setTextSize(18);b.setMinHeight(dp(52));b.setOnClickListener(v->run.run());return b;}
    private void acknowledgePrompt(){if(pendingId!=null)tracker.settle(pendingId);save();}
    private void openConsent(){
        if(pending==null || panel==null)return;
        acknowledgePrompt();
        if(pending.spam){panel.removeAllViews();panel.addView(label("Please ignore this message.",23));panel.addView(label("It may be asking for money, passwords or private details. This warning was made on your phone.",18));panel.addView(action("Close",this::clearPrompt));return;}
        if(pending.noText){panel.removeAllViews();panel.addView(label("Could not read any words",23));panel.addView(label(pending.note.isEmpty()?"Share a clearer picture to Verity.":pending.note,18));panel.addView(action("Close",this::clearPrompt));return;}
        beginCheck(false);
    }
    private void beginCheck(boolean research){
        // SMS has no online route, including retry/research buttons. This guard
        // is independent of which buttons happen to be rendered in the panel.
        if(pending==null || pending.sms)return;String text=pending.text;final long generation=++checkGeneration;
        panel.removeAllViews();overlayStatus=label("Checking…",23);panel.addView(overlayStatus);panel.addView(new ProgressBar(this));panel.addView(action("Close",this::clearPrompt));
        new BackendClient(new AppPrefs(this)).check(text,true,research,new BackendClient.Callback<String>(){
            public void success(String id){if(overlay==null || generation!=checkGeneration)return;overlayJob=id;main.post(pollOverlay);}
            public void failure(String error){if(generation==checkGeneration)showError(error);}
        });
    }
    private void pollResult(){
        if(overlay==null || overlayJob==null)return;final String expected=overlayJob;
        new BackendClient(new AppPrefs(this)).job(expected,new BackendClient.Callback<BackendClient.Job>(){
            public void success(BackendClient.Job result){if(overlay==null || !expected.equals(overlayJob))return;
                if("pending".equals(result.status)){overlayStatus.setText(I18n.text(ForwardAccessibilityService.this,result.stage.contains("link")?"Reading the link…":result.stage.contains("online")?"Checking online…":"Checking…"));main.postDelayed(pollOverlay,250);}
                else if("complete".equals(result.status))showResult(result);else showError(result.error);
            }
            public void failure(String error){if(expected.equals(overlayJob))showError(error);}
        });
    }
    private void showError(String error){if(overlay==null || panel==null)return;overlayJob=null;panel.removeAllViews();panel.addView(label("Could not check",23));panel.addView(label(error==null?"Please try again.":error,18));panel.addView(action("Try again",()->beginCheck(false)));panel.addView(action("Close",this::clearPrompt));}
    private void showResult(BackendClient.Job result){
        if(panel==null)return;boolean quick="quick_model".equals(result.basis),bad=false,good=!result.claims.isEmpty();
        for(BackendClient.Claim claim:result.claims){bad|="contradicted".equals(claim.verdict);good&="supported".equals(claim.verdict)&&(quick||!claim.citations.isEmpty());}
        panel.removeAllViews();TextView title=label(bad?"Looks false":good?"Looks true":"Do not share yet",26);title.setTypeface(null,Typeface.BOLD);title.setTextColor(bad?Ui.RED:good?Ui.GREEN:Ui.AMBER);panel.addView(title);
        panel.addView(label(bad?"Please do not forward this.":good?"The claim appears correct.":"We could not find a clear answer.",18));panel.addView(label(quick?"Quick AI check · can be wrong":"Checked online",14));
        if(pending!=null){TextView preview=rawLabel(pending.text,16);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);panel.addView(preview);}
        LinearLayout row=new LinearLayout(this);row.addView(action("Close",this::clearPrompt),new LinearLayout.LayoutParams(0,-2,1));row.addView(action("Why?",()->showWhy(result)),new LinearLayout.LayoutParams(0,-2,1));panel.addView(row);
    }
    private void showWhy(BackendClient.Job result){
        panel.removeAllViews();boolean quick="quick_model".equals(result.basis);panel.addView(label("About this check",23));
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);panel.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(dp(250),getResources().getDisplayMetrics().heightPixels/3)));
        if(quick){body.addView(label("This is a quick answer from AI. No websites were checked.",18));body.addView(action("Check online",()->beginCheck(true)));}
        else for(BackendClient.Claim claim:result.claims){body.addView(rawLabel(claim.text,18));if(!claim.explanation.isEmpty())body.addView(rawLabel(claim.explanation,18));for(BackendClient.Citation citation:claim.citations){body.addView(rawLabel(citation.quote,17));if(SourceVerifier.publicUrl(citation.url))body.addView(action(citation.title.isEmpty()?"Read source":citation.title,()->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(citation.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception ignored){}}));}}
        if(pending!=null && !pending.note.isEmpty())body.addView(label(pending.note,17));panel.addView(action("Back",()->showResult(result)));
    }
    private void clearPrompt(){checkGeneration++;main.removeCallbacks(pollOverlay);overlayJob=null;panel=null;if(overlay!=null && windows!=null)try{windows.removeView(overlay);}catch(Exception ignored){}overlay=null;pending=null;pendingId=null;pendingChat=null;}
    private void save(){if(choices!=null){String state=tracker.encode();if(!state.equals(choices.getString("state","")))choices.edit().putString("state",state).apply();}}
    public static boolean helperConnected(){return active!=null && active.connected;}
    public static void acknowledgeMessageInstance(Context c,String id){if(id==null)return;if(active!=null){active.tracker.settle(id);active.save();}else{SharedPreferences p=c.getSharedPreferences("message_sequences",MODE_PRIVATE);MessageTracker t=new MessageTracker();t.restore(p.getString("state","{}"));t.settle(id);p.edit().putString("state",t.encode()).apply();}}
    public static void resetDemoPrompts(Context c){c.getSharedPreferences("message_sequences",MODE_PRIVATE).edit().clear().commit();if(active!=null){active.tracker.restore("{}");active.decisions.clear();active.clearPrompt();active.schedule(0);}}
    private boolean allowed(String name){return "com.whatsapp".equals(name)||"com.whatsapp.w4b".equals(name)||(SmsPolicy.supported(name)&&SmsPrefs.enabled(this)&&SmsPrefs.contactsAllowed(this));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void dump(FileDescriptor fd,PrintWriter w,String[] args){w.println("Verity diagnostics — no message or contact content");w.println("connected="+connected);w.println("scans="+scans);w.println("events="+events);w.println("root="+rootPackage);w.println("visible_messages="+(visible==null?0:visible.bubbles.size()));w.println("unsaved="+(visible!=null&&visible.unknown));w.println("selected_contact="+(visible!=null&&visible.selected));w.println("overlay="+(overlay!=null));w.println("local_jobs="+busy.size());w.println("gate="+gate);w.println("last_source="+lastSource);w.println("last_screened_characters="+lastScreenedCharacters);w.println("reason="+reason);
        w.println("sms_enabled="+SmsPrefs.enabled(this));w.println("sms_contacts_allowed="+SmsPrefs.contactsAllowed(this));
        if(visible!=null)for(ChatSnapshot.Bubble b:visible.bubbles)w.println("message: image="+(b.image!=null)+", instance_kind="+(b.instance.isEmpty()?"none":b.instance.substring(0,b.instance.indexOf(':')))+", forwarded="+b.forwarded+", outgoing="+b.outgoing+", eligible="+b.eligible+", settled="+(b.entry!=null&&b.entry.settled)+", known="+(b.entry!=null&&decisions.containsKey(b.entry.id))+", text_chars="+b.text.length());
    }
}

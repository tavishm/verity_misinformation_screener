package org.fairc.forwardcheck.social;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import org.json.*;

/** Opened only by a tap; quick badges never take the user out of their feed. */
public final class SocialResultActivity extends Activity {
    private static final java.util.LinkedHashMap<String,SocialResearchInput> attachments=new java.util.LinkedHashMap<>();
    private static int foregroundReports;
    static synchronized boolean reportForeground(){return foregroundReports>0;}
    static synchronized Intent intent(Context c,SocialPost post,SocialVerdict verdict,SocialResearchInput input){String id=java.util.UUID.randomUUID().toString();attachments.put(id,input);while(attachments.size()>4)attachments.remove(attachments.keySet().iterator().next());return intent(c,post,verdict).putExtra("research_id",id);}
    private SocialResearchInput researchInput(){synchronized(SocialResultActivity.class){SocialResearchInput input=attachments.get(getIntent().getStringExtra("research_id"));return input!=null?input:new SocialResearchInput(getIntent().getStringExtra("text"),"",SocialClient.links(getIntent().getStringExtra("text")));}}

    static Intent intent(Context c,SocialPost post,SocialVerdict verdict){Intent i=new Intent(c,SocialResultActivity.class).putExtra("label",verdict.label()).putExtra("note",verdict.note).putExtra("text",post.text).putExtra("app",post.app).putExtra("media",post.media).putExtra("complete_text",post.completeVisibleText).putExtra("basis",verdict.basis).putExtra("kind",verdict.kind).putExtra("checked",verdict.checkedAt);
        JSONArray a=new JSONArray();for(SocialVerdict.Source s:verdict.sources)try{a.put(new JSONObject().put("title",s.title).put("url",s.url).put("quote",s.quote).put("published",s.published));}catch(Exception ignored){}return i.putExtra("sources",a.toString());}
    @Override public void onCreate(Bundle state){super.onCreate(state);show();}
    @Override protected void onStart(){super.onStart();synchronized(SocialResultActivity.class){foregroundReports++;}}
    @Override protected void onStop(){synchronized(SocialResultActivity.class){foregroundReports=Math.max(0,foregroundReports-1);}super.onStop();}
    private void show(){SocialUi ui=new SocialUi(this);LinearLayout box=ui.column();Intent data=getIntent();box.addView(ui.title(data.getStringExtra("label")));box.addView(ui.text(data.getStringExtra("note"),19));
        TextView claim=ui.text(data.getStringExtra("text"),17);claim.setMaxLines(5);claim.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(ui.text("Message checked",18));box.addView(claim);
        box.addView(ui.text("Checked "+java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(data.getLongExtra("checked",0))),15));
        try{JSONArray sources=new JSONArray(data.getStringExtra("sources"));for(int j=0;j<sources.length();j++){JSONObject s=sources.getJSONObject(j);if(j==0)box.addView(ui.text("What the sources say",20));String quote=s.optString("quote");if(!quote.isEmpty())box.addView(ui.text("“"+quote.substring(0,Math.min(650,quote.length()))+"”",18));String date=s.optString("published");if(!date.isEmpty())box.addView(ui.text("Published "+date.substring(0,Math.min(10,date.length())),14));Button source=ui.button(s.getString("title"),false);String url=s.getString("url");source.setOnClickListener(v->{try{Uri uri=Uri.parse(url);if("https".equals(uri.getScheme()))startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){Toast.makeText(this,"Could not open this source.",Toast.LENGTH_SHORT).show();}});box.addView(source);}}catch(Exception ignored){}
        if(!"skip".equals(data.getStringExtra("kind"))){Button detail=ui.button("Research deeper",false);detail.setOnClickListener(v->{detail.setEnabled(false);detail.setText("Checking sources…");SocialResearchInput input=researchInput();SocialPost post=new SocialPost(data.getStringExtra("app"),data.getStringExtra("text"),0,0,1,1,data.getBooleanExtra("media",false),data.getBooleanExtra("complete_text",false),-1,-1,input.links);SocialClient.get(this).check(post,true,input,result->{if(isDestroyed())return;setIntent(intent(this,post,result,input));show();});});box.addView(detail);}
        Button message=ui.button("Show post",false);message.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Text checked").setMessage(data.getStringExtra("text")).setPositiveButton("Done",null).show());box.addView(message);
        Button done=ui.button("Back to feed",true);done.setOnClickListener(v->finish());box.addView(done);setContentView(ui.screen(box));}
}

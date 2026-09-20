package org.fairc.forwardcheck.social;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.time.YearMonth;
import java.time.ZoneOffset;

/** Separate append-only social accounting. No posts, queries, app names or identifiers. */
final class SocialCosts {
    private final File file;
    private SocialBudget budget;private String month;
    static final class Limit extends IOException {Limit(){super("Monthly checking limit reached.");}}
    SocialCosts(Context context){file=new File(context.getFilesDir(),"social-costs.jsonl");}
    synchronized String start(String model,String mode) throws Exception {
        reload();double reserve="detail".equals(mode)?SocialBudget.DETAIL_RESERVE:"news".equals(mode)?SocialBudget.NEWS_RESERVE:SocialBudget.QUICK_RESERVE;
        if(!budget.canStart(reserve))throw new Limit();
        String id=UUID.randomUUID().toString();
        append(new JSONObject().put("request_id",id).put("model",model).put("mode",mode).put("state","pending").put("reserved_usd",reserve));budget.reserve(id,reserve);return id;
    }
    synchronized void finish(String id,String model,JSONObject data,String state) {
        try { JSONObject row=new JSONObject().put("request_id",id).put("model",model).put("state",state);
            if(data!=null){JSONObject usage=data.optJSONObject("usage");row.put("generation_id",data.optString("id"));
                if(usage!=null){if(usage.has("cost")&&!usage.isNull("cost"))row.put("cost_usd",usage.get("cost"));row.put("input_tokens",usage.optInt("prompt_tokens")).put("output_tokens",usage.optInt("completion_tokens"));}}
            append(row);
            if(budget!=null&&row.has("cost_usd"))budget.settle(id,row.optDouble("cost_usd",Double.NaN));
        } catch(Exception ignored) { /* No private error payloads in logs. */ }
    }
    synchronized String summary(){try{reload();return String.format(java.util.Locale.US,"$%.4f of %s this month\nIncludes reserved costs for unfinished checks. Social checks only.",budget.total(),SocialBudget.limitLabel());}catch(Exception e){return "Cost record is unavailable. Checking is paused.";}}
    private void reload() throws Exception {
        String current=YearMonth.now(ZoneOffset.UTC).toString();if(current.equals(month)&&budget!=null)return;
        SocialBudget loaded=new SocialBudget();
        if(file.exists())try(BufferedReader reader=new BufferedReader(new FileReader(file))){String line;while((line=reader.readLine())!=null){JSONObject r=new JSONObject(line);if(!current.equals(r.optString("month")))continue;String id=r.getString("request_id");if(r.has("reserved_usd"))loaded.reserve(id,r.getDouble("reserved_usd"));if(r.has("cost_usd"))loaded.settle(id,r.getDouble("cost_usd"));}}
        budget=loaded;month=current;
    }
    private void append(JSONObject row) throws Exception {
        row.put("time_ms",System.currentTimeMillis()).put("month",YearMonth.now(ZoneOffset.UTC).toString());
        try(FileOutputStream out=new FileOutputStream(file,true)){out.write((row.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }
}

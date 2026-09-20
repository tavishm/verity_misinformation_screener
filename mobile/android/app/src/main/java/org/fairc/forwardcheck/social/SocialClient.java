package org.fairc.forwardcheck.social;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.fairc.forwardcheck.AppPrefs;
import org.fairc.forwardcheck.SocialLocalGate;
import okhttp3.*;
import org.json.*;
import java.io.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

/** Standalone, opt-in social checks. Shared exact-text work, warm HTTP, no expensive models. */
public final class SocialClient {
    public interface Callback { void result(SocialVerdict verdict); }
    private static SocialClient instance;
    public static synchronized SocialClient get(Context c){if(instance==null)instance=new SocialClient(c.getApplicationContext());return instance;}
    private static final class Cached {final SocialVerdict verdict;final long expires;Cached(SocialVerdict v,long e){verdict=v;expires=e;}}
    private static final class Work {final CompletableFuture<SocialVerdict> result=new CompletableFuture<>();final JSONObject timings=new JSONObject();volatile boolean factual;
        void mark(String phase,long started){try{timings.put(phase,SystemClock.elapsedRealtime()-started);}catch(JSONException ignored){}}
    }
    private final Context context; private final SocialPrefs prefs; private final SocialCosts costs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newFixedThreadPool(SocialPolicy.MAX_ACTIVE_CHECKS),sourceWorkers=Executors.newFixedThreadPool(2*SocialPolicy.MAX_ACTIVE_CHECKS);
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(128,.75f,true);
    private final Map<String,Work> running=new HashMap<>();
    private final Set<Call> calls=Collections.synchronizedSet(new HashSet<>());
    private final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(3,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
            .callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
    private final SocialSources sources=new SocialSources();
    private final SocialNews news;
    private final SocialArticles articles=new SocialArticles();
    private final SocialSearchCache<JSONObject> searches=new SocialSearchCache<>();
    private boolean warmed;
    private SocialClient(Context c){context=c;prefs=new SocialPrefs(c);costs=new SocialCosts(c);news=new SocialNews(new File(c.getCacheDir(),"social-news"));if(prefs.enabled())warm();}
    public synchronized void warm(){if(!prefs.enabled())return;news.warm();if(warmed)return;warmed=true;
        sourceWorkers.execute(()->{try{SocialLocalGate.shouldSkip(context,"Happy Diwali!");}catch(Exception ignored){}});
        sourceWorkers.execute(()->{try{String key=new AppPrefs(context).apiKey();if(key==null||key.isEmpty())return;try(Response response=http.newCall(new Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization","Bearer "+key).build()).execute()){if(response.body()!=null)response.body().string();}}catch(Exception ignored){}});
    }
    public String costSummary(){return costs.summary();}
    public synchronized boolean hasCapacity(){return running.size()<SocialPolicy.MAX_ACTIVE_CHECKS;}
    public synchronized SocialVerdict cached(SocialPost post){return cachedKey(post.key);}
    private synchronized SocialVerdict cachedKey(String key){Cached c=cache.get(key);if(c==null)return null;if(c.expires<SystemClock.elapsedRealtime()){cache.remove(key);return null;}return c.verdict;}
    public void check(SocialPost post,boolean detailed,Callback callback) {
        check(post,detailed,new SocialResearchInput(post.text,"",links(post)),callback);
    }
    static List<String> links(SocialPost post){List<String> out=new ArrayList<>(post.links);out.addAll(links(post.text));return out;}
    static List<String> links(String text){List<String> out=new ArrayList<>();java.util.regex.Matcher m=java.util.regex.Pattern.compile("https://[^\\s<>\"]+").matcher(text);while(m.find())out.add(m.group().replaceFirst("[).,;]+$",""));return out;}
    public void check(SocialPost post,boolean detailed,SocialResearchInput researchInput,Callback callback) {
        if(!prefs.allows(post.app)){main.post(()->callback.result(SocialVerdict.unclear("Screening is off.")));return;}
        final String cacheKey=post.key+(detailed?":detail:"+researchInput.attachmentDigest():"");
        SocialVerdict cached=cachedKey(cacheKey);
        if(cached!=null){main.post(()->callback.result(cached));return;}
        final long revision=prefs.revision();
        Work job;String workKey=revision+":"+cacheKey;
        synchronized(this) {
            job=running.get(workKey);
            if(job==null) {
                if(running.size()>=SocialPolicy.MAX_ACTIVE_CHECKS){main.post(()->callback.result(detailed?SocialVerdict.unclear("Pause here or tap Research deeper."):quiet("screening_busy")));return;}
                job=new Work();running.put(workKey,job);final Work active=job;
                worker.execute(()->{
                    long started=SystemClock.elapsedRealtime();
                    try{active.timings.put("mode",detailed?"detail":"screen").put("complete_text",textComplete(post)).put("has_media",post.media);if(detailed)active.timings.put("image_attached",researchInput.hasImage()).put("link_count",researchInput.links.size());}catch(JSONException ignored){}
                    SocialVerdict result;
                    try{result=review(post,detailed,revision,active,researchInput);}catch(SocialCosts.Limit e){result=detailed||active.factual?SocialVerdict.unclear("Monthly checking limit reached."):quiet("screening_limit");}catch(Exception e){try{active.timings.put("error_type",e.getClass().getSimpleName());}catch(JSONException ignored){}result=SocialVerdict.unclear("Could not check. Try Research deeper.");}
                    if(result.decisive()&&!textComplete(post))result=new SocialVerdict(result.kind,"visible_text","Only the visible text was checked. "+result.note,result.sources);
                    synchronized(SocialClient.this){running.remove(workKey);if(valid(post,revision)){Cached old=cache.get(cacheKey);boolean richer=old!=null&&old.expires>SystemClock.elapsedRealtime()&&"sources".equals(old.verdict.basis)&&!"sources".equals(result.basis);if(!richer){long ttl=result.decisive()?SocialPolicy.ttl(post):"nonfactual".equals(result.basis)?SocialPolicy.STABLE_TTL_MS:15_000;cache.put(cacheKey,new Cached(result,SystemClock.elapsedRealtime()+ttl));}while(cache.size()>128)cache.remove(cache.keySet().iterator().next());}}
                    active.mark("total_ms",started);recordTiming(post,active,result);active.result.complete(result);
                });
            }
        }
        final Work active=job;final CompletableFuture<SocialVerdict> future=job.result;
        // A slow network never holds the feed hostage. The bounded request may
        // finish later and update only the still-matching post, or warm its cache.
        if(!detailed)main.postDelayed(new Runnable(){public void run(){if(future.isDone()||!valid(post,revision))return;if(active.factual)callback.result(new SocialVerdict("checking","searching","Checking sources…",Collections.emptyList()));else main.postDelayed(this,150);}},SocialPolicy.QUICK_VISIBLE_BUDGET_MS);
        future.whenComplete((result,error)->main.post(()->{if(valid(post,revision))callback.result(error==null?result:SocialVerdict.unclear("Could not check."));}));
    }
    public void cancelAll(){searches.clear();synchronized(calls){for(Call c:calls)c.cancel();}synchronized(this){cache.clear();}}
    private boolean valid(SocialPost post,long revision){return prefs.allows(post.app)&&prefs.revision()==revision;}
    private static SocialVerdict quiet(String basis){return new SocialVerdict("skip",basis,"",Collections.emptyList());}
    private synchronized void recordTiming(SocialPost post,Work work,SocialVerdict result){
        try{JSONObject row=new JSONObject(work.timings.toString()).put("app",post.app).put("result",result.kind).put("basis",result.basis).put("time_ms",System.currentTimeMillis());
            File file=new File(context.getFilesDir(),"social-latency.jsonl");
            try(FileOutputStream out=new FileOutputStream(file,file.length()<96_000)){out.write((row.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
    }
    private SocialVerdict review(SocialPost post,boolean detailed,long revision,Work active,SocialResearchInput researchInput) throws Exception {
        if(!valid(post,revision))return SocialVerdict.unclear("Screening is off.");
        boolean fresh=SocialPolicy.freshEvidence(post.text);
        if(!detailed) {
            boolean localPersonal=false;
            if(SocialPolicy.localOpinionGate(post)){
                long localStarted=SystemClock.elapsedRealtime();
                try{localPersonal=SocialLocalGate.shouldSkip(context,post.text);active.factual=!localPersonal;if(localPersonal&&SocialPolicy.clearPersonal(post.text))return quiet("nonfactual");}catch(Exception unavailable){}
                active.mark("local_ms",localStarted);
            }
            news.warm();
            long contextStarted=SystemClock.elapsedRealtime();
            List<SocialVerdict.Source> evidence=news.fastRelated(post.text,20);
            active.mark("context_ms",contextStarted);active.timings.put("source_count",evidence.size());
            active.factual=active.factual||SocialExtractor.NEWS.equals(post.app);
            // News cache is an acceleration, never the only source of evidence.
            // The semantic gate also rescues factual fragments misclassified by
            // the forwarded-message model, while keeping pure opinions quiet.
            if(!evidence.isEmpty()||localPersonal||!checkableText(post)){
                try{
                long screenStarted=SystemClock.elapsedRealtime();
                JSONObject answer=call(post,"screen",asset("social_grounded_prompt.txt"),false,revision,SocialEvidence.screeningInput(post,evidence));
                active.mark("screen_ms",screenStarted);
                active.timings.put("screen_decision",answer.optString("v"));
                if("skip".equals(answer.optString("v")))return quiet("nonfactual");
                active.factual=true;
                SocialVerdict.Source source=SocialEvidence.selectedGrounded(answer,evidence,post.text,true,System.currentTimeMillis());
                active.timings.put("screen_source_accepted",source!=null);
                if(checkableText(post)&&!SocialPolicy.visualClaim(post.text)&&source!=null&&confirmContradiction(post,answer,evidence,revision,active))return new SocialVerdict(answer.optString("v"),"news_excerpt",quickReason(answer.optString("v")),Collections.singletonList(source));
                }catch(SocialCosts.Limit limit){throw limit;}catch(Exception cacheScreenUnavailable){active.timings.put("cache_screen_error",cacheScreenUnavailable.getClass().getSimpleName());}
            }
            // This adapter cannot certify omitted text or uncertain OCR. A
            // short opinion gate is enough here; do not buy search/page work
            // that the completeness guard would inevitably discard afterwards.
            if(!checkableText(post))return SocialVerdict.unclear("The full post needs a closer check. Tap Research deeper.");
            if(SocialPolicy.visualClaim(post.text))return SocialVerdict.unclear("This picture or video needs a closer check. Tap Research deeper.");
            // Every remaining factual candidate receives a real search. Search
            // annotations supply URLs; model-invented URLs cannot enter context.
            long searchStarted=SystemClock.elapsedRealtime();
            String searchKey=revision+":"+SocialPost.digest(post.app+"\n"+java.text.Normalizer.normalize(post.text,java.text.Normalizer.Form.NFKC).replaceAll("\\s+"," "));
            JSONObject search=searches.get(searchKey,SystemClock.elapsedRealtime(),SocialPolicy.live(post.text)?60_000:300_000,()->{
                active.timings.put("search_paid",true);
                return call(post,"news",asset("social_search_prompt.txt")+"\nToday is "+LocalDate.now(ZoneOffset.UTC)+". Search the claim itself, preserving its people, numbers and dates."+(SocialPolicy.visibleTextScope(post)?"\nThis is a VISIBLE TEXT ONLY check of a collapsed X post. Check complete independent factual statements in the visible text, ignoring the Show more interface label. Do not certify the hidden text. If the visible fragment has no complete independent assertion or could reverse its meaning, use research.":""),true,revision);
            });
            active.mark("search_ms",searchStarted);
            // Search may emit a spurious "skip" even while retrieving the exact
            // factual report. Let the evidence decision assess those passages.
            active.factual=true;
            List<SocialVerdict.Source> hits=SocialEvidence.searchHits(search.optJSONArray("_retrieved"),5);
            active.timings.put("search_result_count",hits.size());
            if(hits.isEmpty())return SocialVerdict.unclear("Could not find clear sources. Try Research deeper.");
            // A clear retrieved passage can settle the headline in the search
            // response itself. Validate the actual provider URL and date, then
            // continue fetching full source pages off the visible result path.
            int picked=SocialEvidence.selectedSearch(search,hits);
            if(picked>=0){
                long proofStarted=SystemClock.elapsedRealtime();
                try{
                    SocialVerdict.Source proof=hits.get(picked);
                    if(fresh)proof=sources.verify(new JSONObject().put("url",proof.url).put("quote",proof.quote.substring(0,Math.min(4000,proof.quote.length()))),true,true,SocialPolicy.sourceAgeWindow(post.text));
                    if(proof!=null){
                        List<SocialVerdict.Source> evidenceList=Collections.singletonList(proof);
                        JSONObject indexed=new JSONObject(search.toString()).put("source",0);
                        SocialVerdict.Source accepted=SocialEvidence.selectedGrounded(indexed,evidenceList,post.text,fresh,System.currentTimeMillis());
                        if(accepted!=null&&confirmContradiction(post,indexed,evidenceList,revision,active)){
                            active.mark("search_proof_ms",proofStarted);active.timings.put("direct_search_decision",search.optString("v"));
                            sourceWorkers.execute(()->{if(valid(post,revision))articles.fetch(hits,3200);});
                            return new SocialVerdict(search.optString("v"),"sources",quickReason(search.optString("v")),Collections.singletonList(accepted));
                        }
                    }
                }catch(SocialCosts.Limit limit){throw limit;}catch(Exception unavailable){active.timings.put("search_proof_error",unavailable.getClass().getSimpleName());}
                active.mark("search_proof_ms",proofStarted);
            }
            long fetchStarted=SystemClock.elapsedRealtime();
            List<SocialVerdict.Source> pages=articles.fetch(hits,3200);
            active.mark("article_fetch_ms",fetchStarted);active.timings.put("article_count",pages.size());
            List<SocialVerdict.Source> contextPages=new ArrayList<>();
            for(SocialVerdict.Source hit:hits){SocialVerdict.Source page=null;for(SocialVerdict.Source candidate:pages)if(SocialEvidence.sameSource(hit.url,candidate.url)){page=candidate;break;}contextPages.add(page==null?hit:page);}
            if(fresh)contextPages.removeIf(source->!SocialSources.recent(source.published,System.currentTimeMillis(),SocialPolicy.sourceAgeWindow(post.text)));
            active.timings.put("eligible_source_count",contextPages.size());
            if(contextPages.isEmpty())return SocialVerdict.unclear("Could not find a recent source. Try Research deeper.");
            // One grounded decision over the retrieved articles, not a chain
            // of guesses. Unavailable pages retain their real search excerpts.
            long groundedStarted=SystemClock.elapsedRealtime();
            JSONObject answer=call(post,"grounded",asset("social_grounded_prompt.txt"),false,revision,SocialEvidence.screeningInput(post,contextPages));
            active.mark("grounded_ms",groundedStarted);
            active.timings.put("grounded_decision",answer.optString("v"));
            if("skip".equals(answer.optString("v")))return quiet("nonfactual");
            SocialVerdict.Source source=SocialEvidence.selectedGrounded(answer,contextPages,post.text,fresh,System.currentTimeMillis());
            active.timings.put("grounded_source_accepted",source!=null);
            if(checkableText(post)&&source!=null&&confirmContradiction(post,answer,contextPages,revision,active))return new SocialVerdict(answer.optString("v"),"sources",quickReason(answer.optString("v")),Collections.singletonList(source));
            return SocialVerdict.unclear(post.media?"The picture or full post needs a closer check.":"These sources do not settle this claim. Try Research deeper.");
        }
        long researchStarted=SystemClock.elapsedRealtime();
        JSONObject answer=callBody(post,"detail",true,revision,SocialRequests.body(researchInput,"detail",asset("social_research_prompt.txt")+"\nToday is "+LocalDate.now(ZoneOffset.UTC)+". Assess the supplied search results."));
        active.mark("research_ms",researchStarted);
        active.timings.put("research_decision",answer.optString("v"));
        // Search annotations contain the actual evidence. Reconsider all of it,
        // with complete article text where available, even when the search model
        // initially abstains or formats its citations incorrectly. The final
        // comparison sees the same crop and links, not just a text-only NLI task.
        List<SocialVerdict.Source> hits=SocialEvidence.researchHits(answer);
        if(hits.isEmpty())return new SocialVerdict("uncertain","sources",researchReason(answer,"The search did not return a source for this claim."),Collections.emptyList());
        long fetchStarted=SystemClock.elapsedRealtime();
        List<SocialVerdict.Source> pages=articles.fetch(hits,3200),evidence=new ArrayList<>();
        for(SocialVerdict.Source hit:hits){SocialVerdict.Source expanded=hit;for(SocialVerdict.Source page:pages)if(SocialEvidence.sameSource(hit.url,page.url)){expanded=page;break;}evidence.add(expanded);}
        active.mark("article_fetch_ms",fetchStarted);active.timings.put("article_count",pages.size());
        SocialResearchInput groundedInput=new SocialResearchInput(SocialEvidence.screeningInput(post,evidence),researchInput.jpegDataUrl,researchInput.links);
        long reportStarted=SystemClock.elapsedRealtime();
        JSONObject report=callBody(post,"report",false,revision,SocialRequests.body(groundedInput,"report",asset("social_report_prompt.txt")));
        active.mark("report_ms",reportStarted);active.timings.put("report_decision",report.optString("v"));
        String kind=report.optString("v");
        if("skip".equals(kind))return quiet("nonfactual");
        // Dates are checked only for the selected source; a blocked secondary
        // page must not erase a finding supported by an independent source.
        int index=report.optInt("source",-1);
        if(index>=0&&index<evidence.size()&&("true".equals(kind)||"false".equals(kind))&&fresh){
            SocialVerdict.Source selected=evidence.get(index);
            if(selected.published.isEmpty())try{
                SocialVerdict.Source dated=sources.verify(new JSONObject().put("url",selected.url).put("quote",selected.quote.substring(0,Math.min(4000,selected.quote.length()))),true,true,SocialPolicy.sourceAgeWindow(post.text));
                if(dated!=null)evidence.set(index,new SocialVerdict.Source(selected.title,selected.url,selected.quote,dated.published));
            }catch(Exception unavailable){active.timings.put("publication_unavailable",true);}
        }
        SocialVerdict.Source proof=SocialEvidence.selectedGrounded(report,evidence,post.text,fresh,System.currentTimeMillis());
        active.timings.put("report_source_accepted",proof!=null);
        boolean visual=SocialPolicy.visualClaim(post.text);
        if(proof!=null&&!visual&&confirmContradiction(post,report,evidence,revision,active))return new SocialVerdict(kind,"sources",researchReason(report,quickReason(kind)),Collections.singletonList(proof));
        String reason=researchReason(report,"The reports do not settle this exact claim.");
        if(("true".equals(kind)||"false".equals(kind))&&proof==null)reason=(fresh&&index>=0&&index<evidence.size()?"I could not confirm a recent source for this claim. ":"I could not match the conclusion to a source. ")+reason;
        else if(visual&&("true".equals(kind)||"false".equals(kind)))reason="I found reporting on this topic, but could not verify this particular picture or video. The source text below shows what was confirmed.";
        else if("false".equals(kind))reason="The source does not clearly disprove this claim. It describes related events, but does not establish that this exact statement is wrong. Read the source excerpt below.";
        List<SocialVerdict.Source> references=new ArrayList<>();
        for(SocialVerdict.Source source:evidence){references.add(new SocialVerdict.Source(source.title,source.url,SocialEvidence.literalExcerpt(source.quote,post.text),source.published));if(references.size()==3)break;}
        return new SocialVerdict("uncertain","sources",reason,references);
    }
    private static String quickReason(String kind){return "true".equals(kind)?"The source supports the message. Read the report excerpt below.":"The source contradicts the message. Read what it says below.";}
    private static String researchReason(JSONObject answer,String fallback){String why=answer.optString("why").trim();return why.isEmpty()?fallback:why.substring(0,Math.min(900,why.length()));}
    private static List<SocialVerdict.Source> researchReferences(JSONObject answer){List<SocialVerdict.Source> out=new ArrayList<>();for(SocialVerdict.Source source:SocialEvidence.searchHits(answer.optJSONArray("_retrieved"),2))out.add(new SocialVerdict.Source(source.title,source.url,SocialEvidence.literalExcerpt(source.quote,""),source.published));return out;}
    private static boolean textComplete(SocialPost post){return post.completeVisibleText&&post.text.length()<=4000&&!post.text.endsWith("…")&&!post.text.endsWith("...");}
    private static boolean checkableText(SocialPost post){return textComplete(post)||SocialPolicy.visibleTextScope(post);}
    private boolean confirmContradiction(SocialPost post,JSONObject answer,List<SocialVerdict.Source> evidence,long revision,Work active)throws Exception{
        if(!"false".equals(answer.optString("v")))return true;
        // Cheap one-pass models can mistake missing facts or a different event
        // for a contradiction. A red result needs a separate open-world reading
        // of the same source, without buying another search. True stays one pass.
        int index=answer.optInt("source",-1);if(index<0||index>=evidence.size())return false;
        long started=SystemClock.elapsedRealtime();
        JSONObject support=call(post,"evidence",asset("social_evidence_prompt.txt"),false,revision,SocialEvidence.input(post.text,Collections.singletonList(evidence.get(index))));
        active.mark("false_check_ms",started);active.timings.put("false_check",support.optString("label"));
        return SocialEvidence.supports("false",support.optString("label"));
    }
    private static final class CheckedSource {final int index;final SocialVerdict.Source source;CheckedSource(int i,SocialVerdict.Source s){index=i;source=s;}}
    private List<SocialVerdict.Source> verifiedSupport(SocialPost post,String kind,JSONObject answer,boolean fresh,long revision,Work active)throws Exception{
        JSONArray citations=answer.optJSONArray("citations");List<SocialVerdict.Source> evidence=new ArrayList<>();
        CompletionService<CheckedSource> completed=new ExecutorCompletionService<>(sourceWorkers);List<Future<CheckedSource>> pending=new ArrayList<>();
        long started=SystemClock.elapsedRealtime(),deadline=started+5000;
        try{
            for(int i=0;citations!=null&&i<Math.min(2,citations.length());i++){
                JSONObject item=SocialEvidence.citation(citations.opt(i));if(item==null)continue;
                SocialVerdict.Source retrieved=SocialEvidence.fromSearch(item,answer.optJSONArray("_retrieved"),false);
                String quote=item.optString("quote");if(retrieved==null&&(quote.length()<20||quote.length()>600))continue;
                final int index=i;
                evidence.add(retrieved!=null?retrieved:new SocialVerdict.Source("Read source",item.optString("url"),quote,""));
                pending.add(completed.submit(()->{
                    SocialVerdict.Source verified=null;
                    try{if(valid(post,revision))verified=retrieved!=null?(!fresh?retrieved:sources.verify(new JSONObject().put("url",retrieved.url).put("quote",retrieved.quote),true,true,SocialPolicy.sourceAgeWindow(post.text))):sources.verify(item,fresh,false,SocialPolicy.sourceAgeWindow(post.text));}catch(Exception unavailable){}
                    return new CheckedSource(index,verified);
                }));
            }
            if(evidence.isEmpty())return Collections.emptyList();
            // The existing comparison runs while pages supply publication dates.
            // Its result is provisional until ALL passages used are verified.
            long evidenceStarted=SystemClock.elapsedRealtime();
            JSONObject support=call(post,"evidence",asset("social_evidence_prompt.txt"),false,revision,SocialEvidence.input(post.text,evidence));
            active.mark("evidence_ms",evidenceStarted);
            List<SocialVerdict.Source> verified=new ArrayList<>();
            for(int remaining=pending.size();remaining>0;remaining--){
                Future<CheckedSource> ready=completed.poll();
                if(ready==null){long wait=deadline-SystemClock.elapsedRealtime();if(wait<=0)break;ready=completed.poll(wait,TimeUnit.MILLISECONDS);if(ready==null)break;}
                CheckedSource checked=ready.get();
                if(checked.source==null)continue;
                verified.add(checked.source);
            }
            if(verified.isEmpty())return Collections.emptyList();
            if(verified.size()!=evidence.size()){
                // A blocked/undated source cannot contribute to the verdict.
                // Re-evaluate only the surviving sources, rather than inheriting
                // the provisional comparison that may have depended on it.
                long recheckStarted=SystemClock.elapsedRealtime();
                support=call(post,"evidence",asset("social_evidence_prompt.txt"),false,revision,SocialEvidence.input(post.text,verified));
                active.mark("evidence_recheck_ms",recheckStarted);
            }
            if(SocialEvidence.supports(kind,support.optString("label"))){active.mark("support_ready_ms",started);return verified;}
            return Collections.emptyList();
        }finally{for(Future<CheckedSource> f:pending)if(!f.isDone())f.cancel(true);}
    }
    private JSONObject call(SocialPost post,String mode,String system,boolean web,long revision) throws Exception {
        return call(post,mode,system,web,revision,post.text);
    }
    private JSONObject call(SocialPost post,String mode,String system,boolean web,long revision,String input) throws Exception {
        if(!valid(post,revision))throw new IllegalStateException("Screening is off");
        return callBody(post,mode,web,revision,SocialRequests.body(input,mode,system));
    }
    private JSONObject callBody(SocialPost post,String mode,boolean web,long revision,JSONObject body)throws Exception{
        if(!valid(post,revision))throw new IllegalStateException("Screening is off");
        String model=body.getString("model");
        String key=new AppPrefs(context).apiKey();
        Request request=new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization","Bearer "+key)
                .post(RequestBody.create(body.toString(),MediaType.get("application/json"))).build();
        String id=costs.start(model,mode);boolean settled=false;
        Call call=http.newCall(request);call.timeout().timeout("screen".equals(mode)?3500:"grounded".equals(mode)?6000:"news".equals(mode)?8000:"report".equals(mode)?8000:web?15000:4000,TimeUnit.MILLISECONDS);
        synchronized(calls){if(!valid(post,revision)){costs.finish(id,model,new JSONObject().put("usage",new JSONObject().put("cost",0)),"cancelled_before_send");throw new IOException("Screening is off");}calls.add(call);}
        try(Response response=call.execute()) {
            if(!response.isSuccessful()||response.body()==null){costs.finish(id,model,null,"http_"+response.code());settled=true;throw new IOException("Provider unavailable");}
            JSONObject data=new JSONObject(response.body().string());costs.finish(id,model,data,"complete");settled=true;
            return SocialEvidence.answer(data);
        } finally {calls.remove(call);if(!settled)costs.finish(id,model,null,"charge_unknown");}
    }
    private String asset(String name)throws Exception{try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
}

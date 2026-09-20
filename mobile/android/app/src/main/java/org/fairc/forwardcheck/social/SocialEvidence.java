package org.fairc.forwardcheck.social;

import org.json.*;
import java.net.URI;

/** Search-provider excerpts can verify stable facts, including PDF sources. */
final class SocialEvidence {
    /** One-call opinion screening plus comparison against already-fetched news. */
    static String screeningInput(String claim,java.util.List<SocialVerdict.Source> sources)throws JSONException{
        JSONArray evidence=new JSONArray();
        for(int i=0;i<sources.size();i++){SocialVerdict.Source source=sources.get(i);
            evidence.put(new JSONObject().put("index",i).put("title",source.title).put("url",source.url).put("published",source.published).put("excerpt",source.quote));}
        return new JSONObject().put("claim",claim).put("checked_at_utc",java.time.Instant.now().toString()).put("sources",evidence).toString();
    }
    static String screeningInput(SocialPost post,java.util.List<SocialVerdict.Source> sources)throws JSONException{
        return new JSONObject(screeningInput(post.text,sources)).put("visual_context_unchecked",post.media).put("visible_text_only",SocialPolicy.visibleTextScope(post)).toString();
    }
    /** Reject invented quotes, invalid indices and stale sources before a green/red result. */
    static SocialVerdict.Source selected(JSONObject answer,java.util.List<SocialVerdict.Source> sources,boolean current,long now){
        String verdict=answer.optString("v");if(!"true".equals(verdict)&&!"false".equals(verdict)||!"same_event".equals(answer.optString("relation")))return null;
        int index=answer.optInt("source",-1);if(index<0||index>=sources.size())return null;
        SocialVerdict.Source source=sources.get(index);if(current&&!SocialSources.recent(source.published,now))return null;
        String quote=answer.optString("quote").trim();if(quote.length()<20||quote.length()>400)return null;
        if(!SocialSources.normalized(source.quote).contains(SocialSources.normalized(quote)))return null;
        return source;
    }
    /** Grounding selects an existing fetched source. The app copies its own
     * excerpt, so quote-formatting errors cannot turn a settled fact into unsure. */
    static SocialVerdict.Source selectedGrounded(JSONObject answer,java.util.List<SocialVerdict.Source> sources,String claim,boolean current,long now){
        String kind=answer.optString("v");if(!("true".equals(kind)||"false".equals(kind))||!"same_event".equals(answer.optString("relation")))return null;
        int index=answer.optInt("source",-1);if(index<0||index>=sources.size())return null;
        SocialVerdict.Source source=sources.get(index);if(source.quote.length()<20||current&&!SocialSources.recent(source.published,now,SocialPolicy.sourceAgeWindow(claim)))return null;
        return new SocialVerdict.Source(source.title,source.url,literalExcerpt(source.quote,claim),source.published);
    }
    static String literalExcerpt(String text,String claim){
        if(text.length()<=400)return text;
        java.util.Set<String> words=new java.util.HashSet<>();for(String word:claim.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(word.length()>3)words.add(word);
        int best=0,bestScore=-1;for(int start=0;start<text.length();start+=200){String passage=text.substring(start,Math.min(text.length(),start+400)).toLowerCase(java.util.Locale.ROOT);int score=0;for(String word:words)if(passage.contains(word))score++;if(score>bestScore){best=start;bestScore=score;}}
        return text.substring(best,Math.min(text.length(),best+400));
    }
    static JSONObject citation(Object value)throws JSONException{
        if(value instanceof JSONObject)return (JSONObject)value;
        // Cheap models sometimes return a URL string instead of {"url":...}.
        // This only normalizes the shape; provider matching/page verification
        // below are still mandatory before the URL can support any verdict.
        if(value instanceof String)return new JSONObject().put("url",((String)value).trim());
        return null;
    }
    static String input(String claim,java.util.List<SocialVerdict.Source> sources)throws JSONException{
        StringBuilder premise=new StringBuilder();for(SocialVerdict.Source s:sources){if(!s.published.isEmpty())premise.append("Published ").append(s.published).append(": ");premise.append(s.quote).append("\n");}
        return new JSONObject().put("premise",premise.toString()).put("hypothesis",claim).toString();
    }
    static boolean supports(String verdict,String label){return "true".equals(verdict)&&"entails".equals(label)||"false".equals(verdict)&&"contradicts".equals(label);}
    static int selectedSearch(JSONObject answer,java.util.List<SocialVerdict.Source> sources){
        if(!("true".equals(answer.optString("v"))||"false".equals(answer.optString("v")))||!"same_event".equals(answer.optString("relation")))return -1;
        String url=answer.optString("url");int found=-1;
        for(int i=0;i<sources.size();i++)if(sameSource(url,sources.get(i).url)){if(found>=0)return -1;found=i;}
        return found;
    }
    /** Expand cited real provider results first; invented citation URLs add nothing. */
    static java.util.List<SocialVerdict.Source> researchHits(JSONObject answer){
        java.util.List<SocialVerdict.Source> all=searchHits(answer.optJSONArray("_retrieved"),8),ordered=new java.util.ArrayList<>();
        JSONArray citations=answer.optJSONArray("citations");
        for(int i=0;citations!=null&&i<Math.min(3,citations.length());i++)try{
            JSONObject citation=citation(citations.opt(i));if(citation==null)continue;
            for(SocialVerdict.Source source:all)if(sameSource(citation.optString("url"),source.url)&&!ordered.contains(source))ordered.add(source);
        }catch(JSONException invalid){}
        for(SocialVerdict.Source source:all)if(!ordered.contains(source))ordered.add(source);
        return ordered;
    }
    /** Publisher RSS is independent evidence when an article endpoint blocks
     * automated reads. Dates may enrich only the exact same article URL. */
    static java.util.List<SocialVerdict.Source> researchContext(java.util.List<SocialVerdict.Source> pages,java.util.List<SocialVerdict.Source> publisherItems,String claim,long now){
        java.util.List<SocialVerdict.Source> merged=new java.util.ArrayList<>(pages);
        for(SocialVerdict.Source item:publisherItems){int found=-1;for(int i=0;i<merged.size();i++)if(sameSource(item.url,merged.get(i).url)){found=i;break;}
            if(found<0)merged.add(item);
            else {SocialVerdict.Source page=merged.get(found);if(page.published.isEmpty()&&!item.published.isEmpty())merged.set(found,new SocialVerdict.Source(page.title,page.url,page.quote,item.published));}}
        if(SocialPolicy.freshEvidence(claim)){
            java.util.List<SocialVerdict.Source> dated=new java.util.ArrayList<>();
            for(SocialVerdict.Source s:merged)if(SocialSources.recent(s.published,now,SocialPolicy.sourceAgeWindow(claim)))dated.add(s);
            if(!dated.isEmpty())return dated;
        }
        return merged;
    }
    static JSONObject answer(JSONObject completion)throws Exception {
        JSONObject choice=completion.getJSONArray("choices").getJSONObject(0);
        if(!"stop".equals(choice.optString("finish_reason")))throw new java.io.IOException("Incomplete answer");
        JSONObject message=choice.getJSONObject("message"),answer=new JSONObject(message.getString("content"));
        // Overwrite any similarly named field invented inside model output.
        JSONArray annotations=message.optJSONArray("annotations");
        answer.put("_retrieved",annotations==null?new JSONArray():annotations);return answer;
    }
    static SocialVerdict.Source fromSearch(JSONObject citation,JSONArray annotations,boolean live){
        if(live||annotations==null)return null; // Current claims still need dated source-page verification.
        String url=citation.optString("url");
        try{URI u=new URI(url);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)return null;}catch(Exception bad){return null;}
        for(int i=0;i<annotations.length();i++){
            JSONObject a=annotations.optJSONObject(i);if(a==null||!"url_citation".equals(a.optString("type")))continue;
            JSONObject source=a.optJSONObject("url_citation");if(source==null||!sameSource(url,source.optString("url")))continue;
            // The independent evidence comparison reads the actual retrieved
            // passage, never a quote paraphrased or invented by the first model.
            String excerpt=source.optString("content").trim();
            if(excerpt.length()>=20)return new SocialVerdict.Source(source.optString("title","Read source"),source.optString("url"),excerpt.substring(0,Math.min(4000,excerpt.length())),"");
        }
        return null;
    }
    /** Only actual provider search results, never URLs invented in model output. */
    static java.util.List<SocialVerdict.Source> searchHits(JSONArray annotations,int limit){
        java.util.List<SocialVerdict.Source> hits=new java.util.ArrayList<>();java.util.Set<String> seen=new java.util.HashSet<>();
        for(int i=0;annotations!=null&&i<annotations.length()&&hits.size()<limit;i++){
            JSONObject a=annotations.optJSONObject(i);if(a==null||!"url_citation".equals(a.optString("type")))continue;
            JSONObject source=a.optJSONObject("url_citation");if(source==null)continue;
            String url=source.optString("url"),passage=source.optString("content").trim();
            try{if(!seen.add(sourceKey(url).toString())||passage.length()<20)continue;}catch(Exception invalid){continue;}
            hits.add(new SocialVerdict.Source(source.optString("title","Read source"),url,passage.substring(0,Math.min(12000,passage.length())),""));
        }return hits;
    }
    static boolean sameSource(String first,String second){try{return sourceKey(first).equals(sourceKey(second));}catch(Exception bad){return false;}}
    private static URI sourceKey(String raw)throws Exception{
        URI u=new URI(raw).normalize();if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))throw new IllegalArgumentException("Non-public URL");
        String path=u.getRawPath();if(path==null)path="";while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        // Preserve query parameters and path case; they can select another story.
        return new URI("https",null,u.getHost().toLowerCase(java.util.Locale.ROOT),-1,path,u.getRawQuery(),null);
    }
}

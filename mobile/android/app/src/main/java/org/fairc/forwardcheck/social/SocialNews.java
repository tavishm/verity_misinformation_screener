package org.fairc.forwardcheck.social;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Shared publisher context. Matching feeds are evidence candidates, never verdicts. */
final class SocialNews {
    private static final long REFRESH_MS=300_000L, RETRY_AFTER_FAILURE_MS=45_000L, MAX_CACHE_AGE_MS=86_400_000L;
    private static final int MAX_FEED_BYTES=384_000, MAX_ITEMS_PER_FEED=80, MAX_EXCERPT_CHARS=850;
    private static final Set<String> STOP=new HashSet<>(Arrays.asList("the","and","has","was","for","with","this","that","are","not","from","have","will","you","but","all","can","now","says","said","after","about","into","over","than","their","they","its","who","why","how"));
    /* Verified public HTTPS RSS endpoints, 2026-09-20. Refreshed in the background. */
    private static final Feed[] FEEDS={
        new Feed("BBC News","https://feeds.bbci.co.uk/news/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC US and Canada","https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC Technology","https://feeds.bbci.co.uk/news/technology/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC Science","https://feeds.bbci.co.uk/news/science_and_environment/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC Health","https://feeds.bbci.co.uk/news/health/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC Business","https://feeds.bbci.co.uk/news/business/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC World","https://feeds.bbci.co.uk/news/world/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("BBC News India","https://feeds.bbci.co.uk/news/world/asia/india/rss.xml","bbc.co.uk","bbc.com"),
        new Feed("NDTV","https://feeds.feedburner.com/ndtvnews-top-stories","ndtv.com"),
        new Feed("The Hindu","https://www.thehindu.com/news/national/feeder/default.rss","thehindu.com"),
        new Feed("Times of India","https://timesofindia.indiatimes.com/rssfeedstopstories.cms","timesofindia.indiatimes.com")
    };
    private final ExecutorService workers;
    private final OkHttpClient http;
    private final Feed[] configuredFeeds;
    private final File cacheDir;
    private final Map<String,List<SocialVerdict.Source>> feeds=new HashMap<>();
    private final Map<String,List<IndexedSource>> indexedFeeds=new HashMap<>();
    private List<IndexedSource> index=Collections.emptyList();
    private long lastWarmStarted;

    SocialNews(){this(null,new OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),Executors.newFixedThreadPool(3),FEEDS);}
    SocialNews(File cacheDir){this(cacheDir,new OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),Executors.newFixedThreadPool(3),FEEDS);}
    // Package-visible solely so the HTTP loading boundary can be fixture-tested.
    SocialNews(OkHttpClient http,ExecutorService workers,Feed... configuredFeeds){this(null,http,workers,configuredFeeds);}
    SocialNews(File cacheDir,OkHttpClient http,ExecutorService workers,Feed... configuredFeeds){this.http=http;this.workers=workers;this.configuredFeeds=configuredFeeds.clone();this.cacheDir=cacheDir;loadCache();}
    /** Starts a bounded background refresh; safe to call at service connection and per post. */
    synchronized void warm() {
        long now=System.currentTimeMillis();
        if(lastWarmStarted!=0&&now-lastWarmStarted<(feeds.isEmpty()?RETRY_AFTER_FAILURE_MS:REFRESH_MS))return;
        lastWarmStarted=now;
        for(Feed feed:configuredFeeds)workers.execute(()->load(feed));
    }
    void load(Feed feed){
        try(Response response=http.newCall(new Request.Builder().url(feed.url).header("Accept","application/rss+xml, application/xml;q=0.9").build()).execute()){
            if(!response.isSuccessful()||response.body()==null)return;
            okio.BufferedSource body=response.body().source();
            // request() buffers through the cap or EOF. Unlike readByteArray(n),
            // it does not reject ordinary short RSS documents at EOF.
            body.request(MAX_FEED_BYTES+1L);
            if(body.getBuffer().size()>MAX_FEED_BYTES)return;
            byte[] bytes=body.readByteArray();
            List<SocialVerdict.Source> parsed=parse(new String(bytes,StandardCharsets.UTF_8),System.currentTimeMillis(),feed);
            if(!parsed.isEmpty()){put(feed,parsed);writeCache(feed,bytes);}
        }catch(Exception ignored){/* Optional acceleration: retain last valid cache. */}
    }

    /** Existing API: strictly in-memory, so it makes no per-post HTTP request. */
    synchronized List<SocialVerdict.Source> related(String text){return relatedLocked(text,Long.MAX_VALUE);}
    /** Lookup budget only, not an HTTP timeout. warm() is the sole feed reader. */
    synchronized List<SocialVerdict.Source> fastRelated(String text,long budgetMs){return relatedLocked(text,System.nanoTime()+Math.max(0,budgetMs)*1_000_000L);}
    private List<SocialVerdict.Source> relatedLocked(String text,long deadlineNanos){
        Set<String> query=words(text);if(query.size()<2||System.nanoTime()>deadlineNanos)return Collections.emptyList();
        // Tests and migration callers may seed the legacy map directly. Normal
        // refresh/cache paths maintain the pre-tokenized index incrementally.
        if(index.isEmpty()&&!feeds.isEmpty())rebuildIndex();
        // Retrieval proposes candidates; the model must still prove the exact claim.
        // Extra commentary must not make a relevant short article impossible to retrieve.
        int required=Math.min(4,Math.max(2,(query.size()+1)/2)),checked=0;long now=System.currentTimeMillis();Map<String,Candidate> matches=new LinkedHashMap<>();
        for(IndexedSource item:index){
            if((checked++&15)==0&&System.nanoTime()>deadlineNanos)break;
            if(!freshMillis(item.publishedMillis,now))continue;int score=overlap(query,item.words);if(score<required)continue;
            Candidate old=matches.get(item.source.url);if(old==null||score>old.score)matches.put(item.source.url,new Candidate(item.source,score));
        }
        List<Candidate> ranked=new ArrayList<>(matches.values());ranked.sort((a,b)->Integer.compare(b.score,a.score));List<SocialVerdict.Source> results=new ArrayList<>();
        for(int i=0;i<Math.min(3,ranked.size());i++)results.add(ranked.get(i).source);return results;
    }

    private void put(Feed feed,List<SocialVerdict.Source> parsed){List<IndexedSource> prepared=indexed(parsed);synchronized(this){feeds.put(feed.url,parsed);indexedFeeds.put(feed.url,prepared);rebuildIndex();}}
    private void rebuildIndex(){
        if(indexedFeeds.size()!=feeds.size())for(Map.Entry<String,List<SocialVerdict.Source>> entry:feeds.entrySet())if(!indexedFeeds.containsKey(entry.getKey()))indexedFeeds.put(entry.getKey(),indexed(entry.getValue()));
        List<IndexedSource> combined=new ArrayList<>();for(List<IndexedSource> entries:indexedFeeds.values())combined.addAll(entries);index=Collections.unmodifiableList(combined);
    }
    private static List<IndexedSource> indexed(List<SocialVerdict.Source> sources){List<IndexedSource> out=new ArrayList<>();for(SocialVerdict.Source source:sources)out.add(new IndexedSource(source,Collections.unmodifiableSet(words(source.quote))));return Collections.unmodifiableList(out);}

    private void loadCache(){
        if(cacheDir==null||!cacheDir.isDirectory())return;long now=System.currentTimeMillis();
        for(Feed feed:configuredFeeds)try{
            File file=cacheFile(feed);long length=file.length(),age=now-file.lastModified();if(!file.isFile()||length<=0||length>MAX_FEED_BYTES||age< -300_000||age>MAX_CACHE_AGE_MS)continue;
            byte[] bytes=Files.readAllBytes(file.toPath());if(bytes.length<=0||bytes.length>MAX_FEED_BYTES)continue;
            List<SocialVerdict.Source> parsed=parse(new String(bytes,StandardCharsets.UTF_8),now,feed);if(!parsed.isEmpty())put(feed,parsed);
        }catch(Exception ignored){/* Invalid cache entries never affect the live feed. */}
    }
    private void writeCache(Feed feed,byte[] bytes){
        if(cacheDir==null||bytes.length==0||bytes.length>MAX_FEED_BYTES)return;File temporary=null;
        try{
            if(!cacheDir.isDirectory()&&!cacheDir.mkdirs())return;temporary=File.createTempFile("rss-",".tmp",cacheDir);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            Path target=cacheFile(feed).toPath();try{Files.move(temporary.toPath(),target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary.toPath(),target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception ignored){}finally{if(temporary!=null&&temporary.exists())temporary.delete();}
    }
    private File cacheFile(Feed feed)throws Exception{return new File(cacheDir,"rss-"+hex(MessageDigest.getInstance("SHA-256").digest(feed.url.getBytes(StandardCharsets.UTF_8)))+".xml");}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}

    static List<SocialVerdict.Source> parse(String xml,long now){return parse(xml,now,FEEDS[0]);}
    static List<SocialVerdict.Source> parse(String xml,long now,String feedUrl){for(Feed feed:FEEDS)if(feed.url.equals(feedUrl))return parse(xml,now,feed);return Collections.emptyList();}
    private static List<SocialVerdict.Source> parse(String xml,long now,Feed feed){
        Document doc=Jsoup.parse(xml,"",Parser.xmlParser());List<SocialVerdict.Source> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Element item:doc.select("item")){
            String url=item.select("link").text().trim(),headline=item.select("title").text().trim(),published=isoDate(item.select("pubDate").text());
            if(published==null)published=isoDate(item.select("dc\\:date, date").text());
            if(!fresh(published,now)||headline.length()<10||!articleUrl(url,feed)||!seen.add(url))continue;
            String summary=Jsoup.parse(item.select("description, content\\:encoded").text()).text().trim();String excerpt=headline+(summary.isEmpty()?"":"\n"+summary);
            if(excerpt.length()>MAX_EXCERPT_CHARS)excerpt=excerpt.substring(0,MAX_EXCERPT_CHARS);
            out.add(new SocialVerdict.Source(feed.publisher+": "+headline,url,excerpt,published));if(out.size()>=MAX_ITEMS_PER_FEED)break;
        }return out;
    }
    private static String isoDate(String raw){try{return ZonedDateTime.parse(raw.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();}catch(Exception ignored){}try{return Instant.parse(raw.trim()).toString();}catch(Exception ignored){return null;}}
    private static boolean articleUrl(String value,Feed feed){try{URI uri=URI.create(value);String host=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||host==null||uri.getPath()==null||uri.getPath().length()<2)return false;host=host.toLowerCase(Locale.ROOT);for(String allowed:feed.hosts)if(host.equals(allowed)||host.endsWith("."+allowed))return true;}catch(Exception ignored){}return false;}
    static boolean fresh(String date,long now){try{long age=now-Instant.parse(date).toEpochMilli();return age>=-300_000&&age<=86_400_000;}catch(Exception e){return false;}}
    private static boolean freshMillis(long published,long now){long age=now-published;return published!=Long.MIN_VALUE&&age>=-300_000&&age<=86_400_000;}
    private static long publishedMillis(String date){try{return Instant.parse(date).toEpochMilli();}catch(Exception error){return Long.MIN_VALUE;}}
    private static int overlap(Set<String>a,Set<String>b){int n=0;for(String s:a)if(b.contains(s))n++;return n;}
    private static Set<String> words(String text){Set<String> out=new HashSet<>();for(String s:text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(s.length()>2&&!STOP.contains(s))out.add(s);return out;}
    private static final class IndexedSource {final SocialVerdict.Source source;final Set<String> words;final long publishedMillis;IndexedSource(SocialVerdict.Source source,Set<String> words){this.source=source;this.words=words;this.publishedMillis=publishedMillis(source.published);}}
    private static final class Candidate {final SocialVerdict.Source source;final int score;Candidate(SocialVerdict.Source source,int score){this.source=source;this.score=score;}}
    static final class Feed {final String publisher,url;final String[] hosts;Feed(String publisher,String url,String...hosts){this.publisher=publisher;this.url=url;this.hosts=hosts;}}
}

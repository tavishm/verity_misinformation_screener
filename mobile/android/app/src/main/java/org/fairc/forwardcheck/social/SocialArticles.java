package org.fairc.forwardcheck.social;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded full-text expansion of already-retrieved public HTTPS search hits. */
final class SocialArticles {
    static final int MAX_HITS=5, MAX_BYTES=1_048_576, MAX_TEXT=20_000, MIN_TEXT=200;
    private static final long CACHE_MS=300_000L;
    // Some publishers wrap the entire story in an article-local <header>.
    // The readable region is already narrowed to article/main when possible,
    // so removing every header here can erase the complete source text.
    private static final String REMOVE="script,style,noscript,nav,footer,aside,form,dialog,iframe,svg,canvas,[hidden],.ad,.ads,.advertisement,[class*=advert],[id*=advert],[aria-label*=advert],.cookie-banner,.cookie-consent,.newsletter-signup,.paywall-overlay";
    private static final AtomicInteger THREAD=new AtomicInteger();
    private final ExecutorService workers;
    private final OkHttpClient http;
    private final Loader loader;
    private final LinkedHashMap<String,Cached> cache=new LinkedHashMap<>(48,.75f,true);

    interface Loader { SocialVerdict.Source load(SocialVerdict.Source hit,long deadlineNanos)throws Exception; }
    private static final class Cached {final SocialVerdict.Source source;final long expires;Cached(SocialVerdict.Source s,long e){source=s;expires=e;}}
    private static final class Indexed {final int index;final SocialVerdict.Source source;Indexed(int i,SocialVerdict.Source s){index=i;source=s;}}

    SocialArticles(){this(Executors.newFixedThreadPool(MAX_HITS*SocialPolicy.MAX_ACTIVE_CHECKS,r->{Thread t=new Thread(r,"social-article-"+THREAD.incrementAndGet());t.setDaemon(true);return t;}));}
    SocialArticles(ExecutorService workers){
        this.workers=workers;
        this.http=new OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(3,TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .dns(host->{List<java.net.InetAddress> ips=Dns.SYSTEM.lookup(host);for(java.net.InetAddress ip:ips)if(privateAddress(ip))throw new UnknownHostException("Non-public article host");return ips;}).build();
        this.loader=this::network;
    }
    SocialArticles(ExecutorService workers,Loader loader){this.workers=workers;this.loader=loader;this.http=null;}

    /** Successful article bodies only, in input order. Failures are intentionally omitted. */
    List<SocialVerdict.Source> fetch(List<SocialVerdict.Source> hits,long budgetMs){
        if(hits==null||hits.isEmpty())return Collections.emptyList();
        long now=System.currentTimeMillis(),deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,budgetMs));
        List<SocialVerdict.Source> selected=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(SocialVerdict.Source hit:hits){if(hit==null||selected.size()>=MAX_HITS)break;String key=key(hit.url);if(key!=null&&seen.add(key))selected.add(hit);}
        SocialVerdict.Source[] ordered=new SocialVerdict.Source[selected.size()];
        CompletionService<Indexed> completion=new ExecutorCompletionService<>(workers);List<Future<Indexed>> pending=new ArrayList<>();
        for(int i=0;i<selected.size();i++){
            SocialVerdict.Source hit=selected.get(i);String key=key(hit.url);SocialVerdict.Source cached=cached(key,now);
            if(cached!=null){ordered[i]=cached;continue;}
            if(System.nanoTime()>=deadline)continue;final int index=i;
            pending.add(completion.submit(()->{SocialVerdict.Source source=null;try{source=loader.load(hit,deadline);}catch(Exception ignored){}if(source!=null)put(key,source);return new Indexed(index,source);}));
        }
        try{for(int remaining=pending.size();remaining>0;remaining--){long wait=deadline-System.nanoTime();if(wait<=0)break;Future<Indexed> ready=completion.poll(wait,TimeUnit.NANOSECONDS);if(ready==null)break;Indexed result=ready.get();if(result.source!=null)ordered[result.index]=result.source;}}catch(Exception ignored){}finally{for(Future<Indexed> f:pending)if(!f.isDone())f.cancel(true);}
        List<SocialVerdict.Source> out=new ArrayList<>();for(SocialVerdict.Source source:ordered)if(source!=null)out.add(source);return out;
    }

    private SocialVerdict.Source network(SocialVerdict.Source hit,long deadline)throws Exception{
        String url=hit.url;
        for(int redirects=0;redirects<=3;redirects++){
            if(key(url)==null)return null;long remaining=deadline-System.nanoTime();if(remaining<=0)return null;
            Request request=new Request.Builder().url(url).header("User-Agent","ForwardCheck/0.3 article reader").header("Accept","text/html,application/xhtml+xml").build();
            Call call=http.newCall(request);call.timeout().timeout(Math.min(remaining,TimeUnit.SECONDS.toNanos(3)),TimeUnit.NANOSECONDS);
            try(Response response=call.execute()){
                if(response.isRedirect()){
                    if(redirects==3)return null;String location=response.header("Location");if(location==null)return null;
                    url=new URI(url).resolve(location).toString();continue;
                }
                String type=response.header("Content-Type","").toLowerCase(Locale.ROOT);
                if(!response.isSuccessful()||response.body()==null||!(type.contains("text/html")||type.contains("application/xhtml+xml")))return null;
                // A long advertising tail must not discard an already-read
                // article when the response reaches the byte/time budget.
                byte[] bytes=readBounded(response.body().source());String charset=response.body().contentType()!=null&&response.body().contentType().charset()!=null?response.body().contentType().charset().name():StandardCharsets.UTF_8.name();
                return parse(hit,new String(bytes,charset),url);
            }
        }
        return null;
    }

    static byte[] readBounded(okio.BufferedSource body)throws java.io.IOException{
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];
        try{while(bytes.size()<MAX_BYTES){int n=body.read(buffer,0,Math.min(buffer.length,MAX_BYTES-bytes.size()));if(n<0)break;bytes.write(buffer,0,n);}}
        catch(java.io.IOException unavailable){if(bytes.size()==0)throw unavailable;}
        return bytes.toByteArray();
    }

    static SocialVerdict.Source parse(SocialVerdict.Source hit,String html,String finalUrl){
        if(hit==null||html==null||key(finalUrl)==null)return null;
        Document document=Jsoup.parse(html,finalUrl);String published=SocialSources.publication(document);
        String title=content(document,"meta[property=og:title]");if(title.isEmpty())title=clean(document.title());if(title.isEmpty()){Element h1=document.selectFirst("h1");title=h1==null?"":clean(h1.text());}if(title.isEmpty())title=hit.title;
        Element region=largest(document.select("article"));if(region==null)region=largest(document.select("main,[role=main]"));if(region==null)region=document.body();if(region==null)return null;
        Element readable=region.clone();readable.select(REMOVE).remove();String text=clean(readable.text());
        if(text.length()<MIN_TEXT||boilerplate(text))return null;
        if(text.length()>MAX_TEXT){int cut=text.lastIndexOf(' ',MAX_TEXT);if(cut<MAX_TEXT-1000)cut=MAX_TEXT;text=text.substring(0,cut).trim();}
        // Keep the provider-supplied canonical URL as the join key used by the
        // prompt builder. Redirect targets were validated and supplied the
        // bytes, but replacing this URL would orphan the expanded passage.
        return new SocialVerdict.Source(title,hit.url,text,published);
    }

    private static Element largest(org.jsoup.select.Elements elements){Element best=null;int length=-1;for(Element e:elements){int n=e.text().length();if(n>length){best=e;length=n;}}return best;}
    private static String content(Document d,String selector){Element e=d.selectFirst(selector);return e==null?"":clean(e.attr("content"));}
    private static boolean boilerplate(String text){String value=text.toLowerCase(Locale.ROOT);return (value.contains("enable javascript")||value.contains("access denied")||value.contains("page not found")||value.contains("subscribe to continue")||value.contains("sign in to continue")||value.contains("accept cookies to continue"))&&text.length()<1200;}
    private static String clean(String value){return value==null?"":value.replace('\u00a0',' ').replaceAll("\\s+"," ").trim();}

    private synchronized SocialVerdict.Source cached(String key,long now){Cached value=cache.get(key);if(value==null)return null;if(value.expires<now){cache.remove(key);return null;}return value.source;}
    private synchronized void put(String key,SocialVerdict.Source source){cache.put(key,new Cached(source,System.currentTimeMillis()+CACHE_MS));while(cache.size()>40)cache.remove(cache.keySet().iterator().next());}
    static String key(String raw){try{URI u=new URI(raw).normalize();if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return null;String path=u.getRawPath();if(path==null||path.isEmpty())path="/";return new URI("https",null,u.getHost().toLowerCase(Locale.ROOT),-1,path,u.getRawQuery(),null).toString();}catch(Exception bad){return null;}}
    private static boolean privateAddress(java.net.InetAddress ip){byte[] a=ip.getAddress();return ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(a.length==16&&(a[0]&0xfe)==0xfc);}
}

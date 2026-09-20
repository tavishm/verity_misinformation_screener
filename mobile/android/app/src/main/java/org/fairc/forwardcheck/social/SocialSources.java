package org.fairc.forwardcheck.social;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.json.*;
import java.net.*;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Re-fetch literal source passages; a model-provided URL alone is not evidence. */
final class SocialSources {
    private final OkHttpClient http=new OkHttpClient.Builder().connectTimeout(2,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS)
            .readTimeout(3,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(host->{List<InetAddress> ips=Dns.SYSTEM.lookup(host);for(InetAddress ip:ips)if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(ip.getAddress().length==16&&(ip.getAddress()[0]&0xfe)==0xfc))throw new java.net.UnknownHostException("Non-public source");return ips;}).build();
    SocialVerdict.Source verify(JSONObject item,boolean live) throws Exception {
        return verify(item,live,false);
    }
    SocialVerdict.Source verify(JSONObject item,boolean live,boolean retrievedPassage) throws Exception {
        return verify(item,live,retrievedPassage,86_400_000L);
    }
    SocialVerdict.Source verify(JSONObject item,boolean live,boolean retrievedPassage,long maxAge) throws Exception {
        String url=item.optString("url"),quote=item.optString("quote");
        if(quote.length()<20||quote.length()>(retrievedPassage?4000:600))return null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        for(int redirects=0;redirects<3;redirects++) {
            URI uri=new URI(url);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return null;
            long remaining=deadline-System.nanoTime();if(remaining<=0)return null;
            Call request=http.newCall(new Request.Builder().url(url).header("User-Agent","ForwardCheck/0.2 source-check").build());request.timeout().timeout(remaining,TimeUnit.NANOSECONDS);
            try(Response response=request.execute()) {
                if(response.isRedirect()){String next=response.header("Location");if(next==null)return null;url=uri.resolve(next).toString();continue;}
                if(!response.isSuccessful()||response.body()==null)return null;
                if(!response.header("Content-Type","").toLowerCase(Locale.ROOT).contains("text/html"))return null;
                Document doc=readDocument(response.body().source(),url,retrievedPassage);
                String published=publication(doc);
                if(live&&!recent(published,System.currentTimeMillis(),maxAge))return null;
                doc.select("script,style,noscript,nav,footer").remove();
                if(!retrievedPassage&&!normalized(doc.text()).contains(normalized(quote)))return null;
                return new SocialVerdict.Source(doc.title().isEmpty()?uri.getHost():doc.title(),url,quote,published);
            }
        }
        return null;
    }
    // Search already supplied the literal passage. In that path only publication
    // metadata is missing: do not wait for half a megabyte of article/ad scripts.
    static Document readDocument(okio.BufferedSource body,String url,boolean metadataOnly)throws java.io.IOException{
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[8192];
        Document doc=Jsoup.parse("",url);
        while(bytes.size()<512_000){int n=body.read(chunk,0,Math.min(chunk.length,512_000-bytes.size()));if(n<0)break;
            bytes.write(chunk,0,n);
            if(metadataOnly){String prefix=bytes.toString("UTF-8");int closed=prefix.lastIndexOf('>');
                // A network chunk can end halfway through a date attribute.
                // Only inspect complete markup, otherwise a partial date could
                // incorrectly end the read and make a fresh page look undated.
                if(closed>=0){doc=Jsoup.parse(prefix.substring(0,closed+1),url);if(!publication(doc).isEmpty())return doc;}}
        }
        return Jsoup.parse(bytes.toString("UTF-8"),url);
    }
    static String publication(Document doc){
        for(String selector:new String[]{"meta[property=article:published_time]","meta[name=date]","meta[name=datePublished]","meta[itemprop=datePublished]","time[datetime]"}){
            Element e=doc.selectFirst(selector);if(e!=null){String value=e.hasAttr("content")?e.attr("content"):e.attr("datetime");if(!value.isEmpty())return value;}}
        for(Element script:doc.select("script[type=application/ld+json]"))try{String value=publicationJson(new JSONTokener(script.data()).nextValue(),0);if(!value.isEmpty())return value;}catch(Exception ignored){}
        return "";
    }
    private static String publicationJson(Object value,int depth){
        if(depth>5)return "";
        if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<Math.min(a.length(),50);i++){String date=publicationJson(a.opt(i),depth+1);if(!date.isEmpty())return date;}}
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;String type=o.optString("@type");
            if(type.contains("Article")||type.contains("BlogPosting")){String date=o.optString("datePublished");if(!date.isEmpty())return date;}
            // Do not borrow publication dates from unrelated recommended stories.
            for(String key:new String[]{"@graph","mainEntity"}){String date=publicationJson(o.opt(key),depth+1);if(!date.isEmpty())return date;}}
        return "";
    }
    static boolean recent(String date,long now){
        return recent(date,now,86_400_000L);
    }
    static boolean recent(String date,long now,long maxAge){
        Instant instant;
        try{instant=Instant.parse(date);}catch(Exception e){try{instant=OffsetDateTime.parse(date).toInstant();}catch(Exception e2){try{instant=LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();}catch(Exception e3){return false;}}}
        long age=now-instant.toEpochMilli();return age>=-300_000&&age<=maxAge;
    }
    static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).replaceAll("\\s+"," ").trim();}
}

package org.fairc.forwardcheck.social;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SocialArticlesTest {
    @Test public void articleBeforeLongAdvertisingTailIsNotDiscarded()throws Exception{
        String html="<article>"+words(10)+"</article>";okio.Buffer body=new okio.Buffer().writeUtf8(html);body.write(new byte[SocialArticles.MAX_BYTES]);
        byte[] read=SocialArticles.readBounded(body);assertEquals(SocialArticles.MAX_BYTES,read.length);assertTrue(body.size()>0);
        assertNotNull(SocialArticles.parse(hit("https://news.example/story"),new String(read,java.nio.charset.StandardCharsets.UTF_8),"https://news.example/story"));
    }
    @Test public void completedArticleSurvivesTimeoutInPageTail()throws Exception{
        String html="<article>"+words(10)+"</article>";okio.Buffer source=new okio.Buffer().writeUtf8(html);
        okio.BufferedSource body=okio.Okio.buffer(new okio.ForwardingSource(source){@Override public long read(okio.Buffer sink,long count)throws java.io.IOException{if(source.size()==0)throw new java.net.SocketTimeoutException("ad tail");return super.read(sink,count);}});
        assertEquals(html,new String(SocialArticles.readBounded(body),java.nio.charset.StandardCharsets.UTF_8));
    }
    private static SocialVerdict.Source hit(String url){return new SocialVerdict.Source("Search title",url,"Short search excerpt","2026-09-20T01:00:00Z");}
    private static String words(int count){StringBuilder s=new StringBuilder();for(int i=0;i<count;i++)s.append("documented article sentence number ").append(i).append(" contains useful reporting. ");return s.toString();}

    @Test public void articleHeadlineBodyAndPublicationAreRetained(){
        String html="<html><head><title>Fallback title</title><meta property='og:title' content='Exact article headline'><meta property='article:published_time' content='2026-09-20T02:03:04Z'></head><body><header>Site header</header><main><article><h1>Exact article headline</h1><p>"+words(12)+"</p><nav>Related menu</nav><script>fabricated claim</script></article></main><footer>Footer links</footer></body></html>";
        SocialVerdict.Source source=SocialArticles.parse(hit("https://news.example/story"),html,"https://news.example/story");
        assertNotNull(source);assertEquals("Exact article headline",source.title);assertEquals("2026-09-20T02:03:04Z",source.published);assertTrue(source.quote.contains("documented article sentence"));assertFalse(source.quote.contains("Related menu"));assertFalse(source.quote.contains("fabricated claim"));assertFalse(source.quote.contains("Site header"));
    }

    @Test public void largestArticleWinsAndBodyFallbackWorks(){
        String html="<body><article><p>Short teaser only.</p></article><article><h1>Report</h1><p>"+words(10)+"</p></article></body>";
        SocialVerdict.Source source=SocialArticles.parse(hit("https://news.example/report"),html,"https://news.example/report");assertNotNull(source);assertTrue(source.quote.contains("sentence number 9"));assertFalse(source.quote.contains("Short teaser"));
        assertNotNull(SocialArticles.parse(hit("https://news.example/plain"),"<body><h1>Plain report</h1><div>"+words(10)+"</div></body>","https://news.example/plain"));
    }

    @Test public void articleLocalHeaderCanContainTheWholeStory(){
        String html="<body><header>Site navigation</header><article><header class='article-header'><h1>Airbag judgment</h1><p>"+words(10)+"</p></header></article></body>";
        SocialVerdict.Source source=SocialArticles.parse(hit("https://news.example/judgment"),html,"https://news.example/judgment");
        assertNotNull(source);assertTrue(source.quote.contains("Airbag judgment"));assertTrue(source.quote.contains("sentence number 9"));assertFalse(source.quote.contains("Site navigation"));
    }

    @Test public void tinyErrorCookieAndPaywallPagesAreRejected(){
        assertNull(SocialArticles.parse(hit("https://news.example/tiny"),"<article>Brief page.</article>","https://news.example/tiny"));
        assertNull(SocialArticles.parse(hit("https://news.example/error"),"<body>Access denied. "+words(4)+"</body>","https://news.example/error"));
        assertNull(SocialArticles.parse(hit("https://news.example/paywall"),"<main>Subscribe to continue. "+words(4)+"</main>","https://news.example/paywall"));
    }

    @Test public void articleTextIsBoundedWithoutInventedSuffix(){
        String full=words(800);SocialVerdict.Source source=SocialArticles.parse(hit("https://news.example/long"),"<article>"+full+"</article>","https://news.example/long");assertNotNull(source);assertTrue(source.quote.length()<=SocialArticles.MAX_TEXT);assertTrue(full.startsWith(source.quote));assertFalse(source.quote.contains("truncated"));
    }

    @Test public void urlPolicyRejectsNonPublicShapes(){
        assertEquals("https://news.example/story?a=1",SocialArticles.key("HTTPS://NEWS.EXAMPLE/story?a=1#part"));
        assertNull(SocialArticles.key("http://news.example/story"));assertNull(SocialArticles.key("https://user@news.example/story"));assertNull(SocialArticles.key("https://news.example:444/story"));
    }

    @Test public void safeRedirectTargetDoesNotChangeSearchHitJoinKey(){
        SocialVerdict.Source source=SocialArticles.parse(hit("https://news.example/provider-link"),"<article>"+words(8)+"</article>","https://www.news.example/final-story");
        assertNotNull(source);assertEquals("https://news.example/provider-link",source.url);
    }

    @Test public void fetchIsConcurrentOrderedCachedAndLimited()throws Exception{
        ExecutorService workers=Executors.newFixedThreadPool(5);AtomicInteger loads=new AtomicInteger();
        SocialArticles articles=new SocialArticles(workers,(source,deadline)->{loads.incrementAndGet();Thread.sleep(source.url.endsWith("/1")?60:10);return new SocialVerdict.Source(source.title,source.url,words(5),source.published);});
        try{
            List<SocialVerdict.Source> hits=new ArrayList<>();for(int i=0;i<7;i++)hits.add(hit("https://news.example/"+i));
            List<SocialVerdict.Source> first=articles.fetch(hits,1000);assertEquals(5,first.size());for(int i=0;i<5;i++)assertEquals("https://news.example/"+i,first.get(i).url);assertEquals(5,loads.get());
            assertEquals(5,articles.fetch(hits,0).size());assertEquals(5,loads.get());
        }finally{workers.shutdownNow();}
    }

    @Test public void failedAndOverBudgetHitsAreOmitted()throws Exception{
        ExecutorService workers=Executors.newFixedThreadPool(2);SocialArticles articles=new SocialArticles(workers,(source,deadline)->{if(source.url.endsWith("bad"))return null;Thread.sleep(100);return new SocialVerdict.Source(source.title,source.url,words(5),source.published);});
        try{assertTrue(articles.fetch(Arrays.asList(hit("https://news.example/bad"),hit("https://news.example/slow")),10).isEmpty());}finally{workers.shutdownNow();}
    }
}

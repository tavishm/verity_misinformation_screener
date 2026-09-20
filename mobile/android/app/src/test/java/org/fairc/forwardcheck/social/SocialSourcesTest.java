package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import org.jsoup.Jsoup;
import java.time.Instant;
public class SocialSourcesTest {
    @Test public void yesterdayReportRemainsEligibleForOngoingStoryButNotCurrentDeathStatus(){long now=Instant.parse("2026-09-20T05:45:00Z").toEpochMilli();String date="2026-09-19T07:47:19+05:30";assertTrue(SocialSources.recent(date,now,SocialPolicy.sourceAgeWindow("Mother of IIT Bombay student who died by suicide")));assertFalse(SocialSources.recent(date,now,SocialPolicy.sourceAgeWindow("Trump is dead")));assertFalse(SocialSources.recent("2026-09-01T07:47:19+05:30",now,SocialPolicy.sourceAgeWindow("Mother of IIT Bombay student who died by suicide")));}
    @Test public void readsArticleStructuredPublication(){assertEquals("2026-09-19T20:00:00Z",SocialSources.publication(Jsoup.parse("<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"NewsArticle\",\"datePublished\":\"2026-09-19T20:00:00Z\"}]}</script>")));}
    @Test public void modifiedDateCannotMakeAnOldStoryFresh(){assertEquals("",SocialSources.publication(Jsoup.parse("<script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"dateModified\":\"2026-09-19\"}</script>")));}
    @Test public void unrelatedRecommendationDatesAreNotBorrowed(){assertEquals("",SocialSources.publication(Jsoup.parse("<script type='application/ld+json'>{\"@type\":\"WebPage\",\"recommendations\":[{\"@type\":\"NewsArticle\",\"datePublished\":\"2026-09-19\"}]}</script>")));}
    @Test public void freshnessRequiresARealRecentPublication(){long now=Instant.parse("2026-09-19T22:00:00Z").toEpochMilli();assertTrue(SocialSources.recent("2026-09-19T20:00:00Z",now));assertTrue(SocialSources.recent("2026-09-19",now));assertFalse(SocialSources.recent("2020-09-19",now));assertFalse(SocialSources.recent("2026-09-21",now));assertFalse(SocialSources.recent("",now));}
    @Test public void metadataDoesNotWaitForArticleAndAdvertisingScripts()throws Exception{
        okio.Buffer body=new okio.Buffer().writeUtf8("<head><meta property='article:published_time' content='2026-09-19T20:00:00Z'></head>");body.write(new byte[600_000]);
        assertEquals("2026-09-19T20:00:00Z",SocialSources.publication(SocialSources.readDocument(body,"https://example.org/news",true)));
        assertTrue("Most of the response must remain unread",body.size()>590_000);
    }
    @Test public void chunkBoundaryCannotTruncatePublicationDate()throws Exception{
        okio.Buffer bytes=new okio.Buffer().writeUtf8("<head><meta property='article:published_time' content='2026-09-19T20:00:00Z'></head>");bytes.write(new byte[100_000]);
        okio.BufferedSource chunks=okio.Okio.buffer(new okio.ForwardingSource(bytes){@Override public long read(okio.Buffer sink,long count)throws java.io.IOException{return super.read(sink,Math.min(count,23));}});
        assertEquals("2026-09-19T20:00:00Z",SocialSources.publication(SocialSources.readDocument(chunks,"https://example.org/news",true)));
        assertTrue(bytes.size()>99_000);
    }
    @Test public void literalQuoteVerificationStillReadsArticleText()throws Exception{
        okio.Buffer body=new okio.Buffer().writeUtf8("<meta name='date' content='2026-09-19'><article>The source says Earth has one natural moon.</article>");
        assertTrue(SocialSources.readDocument(body,"https://example.org/news",false).text().contains("Earth has one natural moon"));
    }
    @Test public void missingMetadataRemainsBounded()throws Exception{
        okio.Buffer body=new okio.Buffer();body.write(new byte[520_000]);body.writeUtf8("<meta name='date' content='2026-09-19'>");
        assertEquals("",SocialSources.publication(SocialSources.readDocument(body,"https://example.org/news",true)));assertTrue(body.size()>=8000);
    }
}

package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;

public class SocialEvidenceTest {
    @Test public void deepExpansionPrioritizesRealCitationsAndKeepsAllEightResults()throws Exception{
        JSONArray retrieved=new JSONArray();for(int i=0;i<8;i++)retrieved.put(new JSONObject().put("type","url_citation").put("url_citation",new JSONObject().put("url","https://example.org/"+i).put("content","A sufficiently long actual provider passage "+i)));
        JSONObject answer=new JSONObject().put("_retrieved",retrieved).put("citations",new JSONArray().put("https://invented.example/article").put("https://example.org/7"));
        java.util.List<SocialVerdict.Source> ordered=SocialEvidence.researchHits(answer);
        assertEquals(8,ordered.size());assertEquals("https://example.org/7",ordered.get(0).url);
        for(SocialVerdict.Source s:ordered)assertFalse(s.url.contains("invented"));
    }
    @Test public void combinedSearchCannotPublishWrongEventOrInventedSource()throws Exception{java.util.List<SocialVerdict.Source> hits=java.util.Collections.singletonList(new SocialVerdict.Source("A report","https://publisher.example/story","The court explicitly upheld the consumer refund order.",""));JSONObject answer=new JSONObject().put("v","true").put("relation","same_event").put("url",hits.get(0).url);assertEquals(0,SocialEvidence.selectedSearch(answer,hits));assertEquals(-1,SocialEvidence.selectedSearch(new JSONObject(answer.toString()).put("url","https://invented.example/story"),hits));assertEquals(-1,SocialEvidence.selectedSearch(new JSONObject(answer.toString()).put("v","false").put("relation","different_event"),hits));assertEquals(-1,SocialEvidence.selectedSearch(new JSONObject().put("v","false").put("relation","different_event").put("url",""),hits));assertEquals(-1,SocialEvidence.selectedSearch(new JSONObject(answer.toString()).put("v","research"),hits));}
    String quote="Earth has one natural moon, and Mars has its two small moons.";
    JSONObject citation()throws Exception{return new JSONObject().put("url","https://www.nasa.gov/moons.pdf").put("quote",quote);}
    JSONArray retrieved()throws Exception{return new JSONArray().put(new JSONObject().put("type","url_citation").put("url_citation",new JSONObject().put("url","https://www.nasa.gov/moons.pdf").put("title","Moons").put("content",quote)));}
    @Test public void stablePdfExcerptCanBeUsed()throws Exception{assertNotNull(SocialEvidence.fromSearch(citation(),retrieved(),false));}
    @Test public void plainUrlCitationsRetainProviderProvenance()throws Exception{
        assertNotNull(SocialEvidence.fromSearch(SocialEvidence.citation("https://www.nasa.gov/moons.pdf"),retrieved(),false));
        assertNull(SocialEvidence.fromSearch(SocialEvidence.citation("https://invented.example/moons.pdf"),retrieved(),false));
        assertNull(SocialEvidence.citation(5));assertNull(SocialEvidence.citation(JSONObject.NULL));
    }
    @Test public void inventedQuoteCannotEnterEvidence()throws Exception{SocialVerdict.Source s=SocialEvidence.fromSearch(citation().put("quote",quote.replace("one","two")),retrieved(),false);assertNotNull(s);assertEquals(quote,s.quote);assertNull(SocialEvidence.fromSearch(citation().put("url","https://other.example/"),retrieved(),false));}
    @Test public void undatedSearchDoesNotCertifyCurrentEvents()throws Exception{assertNull(SocialEvidence.fromSearch(citation(),retrieved(),true));}
    @Test public void modelCannotForgeRetrievedAnnotations()throws Exception{JSONObject content=new JSONObject().put("v","false").put("_retrieved",retrieved());JSONObject message=new JSONObject().put("content",content.toString());JSONObject completion=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",message)));assertEquals(0,SocialEvidence.answer(completion).getJSONArray("_retrieved").length());}
    @Test public void trustedTransportAnnotationsSurvive()throws Exception{JSONObject message=new JSONObject().put("content","{\"v\":\"false\"}").put("annotations",retrieved());JSONObject completion=new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",message)));assertNotNull(SocialEvidence.fromSearch(citation(),SocialEvidence.answer(completion).getJSONArray("_retrieved"),false));}
    @Test public void absenceOfSupportIsNotFalse(){assertFalse(SocialEvidence.supports("false","neutral"));assertFalse(SocialEvidence.supports("true","neutral"));assertFalse(SocialEvidence.supports("false","entails"));assertTrue(SocialEvidence.supports("false","contradicts"));assertTrue(SocialEvidence.supports("true","entails"));}
    @Test public void evidenceAndClaimHaveSeparateFields()throws Exception{JSONObject j=new JSONObject(SocialEvidence.input("Claim with 20 people",java.util.Collections.singletonList(new SocialVerdict.Source("Title","https://example.org","Evidence says 10 people",""))));assertEquals("Claim with 20 people",j.getString("hypothesis"));assertTrue(j.getString("premise").contains("10 people"));assertFalse(j.getString("premise").contains("20 people"));}
    @Test public void sourceMatchingIgnoresOnlyHarmlessUrlDifferences(){assertTrue(SocialEvidence.sameSource("https://www.nasa.gov/story/","https://www.nasa.gov/story#section"));assertFalse(SocialEvidence.sameSource("https://www.nasa.gov/story?id=1","https://www.nasa.gov/story?id=2"));assertFalse(SocialEvidence.sameSource("https://www.nasa.gov/story","https://www.nasa.gov.bad.example/story"));assertFalse(SocialEvidence.sameSource("https://www.nasa.gov@bad.example/story","https://www.nasa.gov/story"));}
    @Test public void combinedInputKeepsClaimAndIndexedSourcesSeparate()throws Exception{JSONObject j=new JSONObject(SocialEvidence.screeningInput("Claim",java.util.Collections.singletonList(new SocialVerdict.Source("Title","https://example.org","A sufficiently long literal evidence passage.","2026-09-20T00:00:00Z"))));assertEquals("Claim",j.getString("claim"));assertEquals(0,j.getJSONArray("sources").getJSONObject(0).getInt("index"));}
    @Test public void combinedSelectionRequiresLiteralFreshProvenance()throws Exception{
        long now=java.time.Instant.parse("2026-09-20T01:00:00Z").toEpochMilli();SocialVerdict.Source source=new SocialVerdict.Source("Title","https://example.org","Police explicitly said no arrests occurred in Delhi on September 19.","2026-09-20T00:00:00Z");java.util.List<SocialVerdict.Source> sources=java.util.Collections.singletonList(source);
        JSONObject valid=new JSONObject().put("v","false").put("relation","same_event").put("source",0).put("quote","no arrests occurred in Delhi on September 19");assertSame(source,SocialEvidence.selected(valid,sources,true,now));
        assertNull(SocialEvidence.selected(new JSONObject(valid.toString()).put("relation","different_event"),sources,true,now));
        assertNull(SocialEvidence.selected(new JSONObject(valid.toString()).put("source",3),sources,true,now));assertNull(SocialEvidence.selected(new JSONObject(valid.toString()).put("quote","A fabricated quote that never appeared in the source"),sources,true,now));assertNull(SocialEvidence.selected(valid,java.util.Collections.singletonList(new SocialVerdict.Source("Old","https://example.org/old",source.quote,"2020-01-01")),true,now));assertNull(SocialEvidence.selected(new JSONObject().put("v","research").put("source",0).put("quote",source.quote),sources,true,now));
    }
    @Test public void searchHitsAreBoundedDeduplicatedAndRequireRealPassages()throws Exception{
        JSONArray rows=retrieved();rows.put(rows.get(0));rows.put(new JSONObject().put("type","url_citation").put("url_citation",new JSONObject().put("url","https://example.org/next").put("content","A second actual retrieved passage with information.")));
        rows.put(new JSONObject().put("type","url_citation").put("url_citation",new JSONObject().put("url","http://insecure.example/page").put("content",quote)));
        rows.put(new JSONObject().put("type","url_citation").put("url_citation",new JSONObject().put("url","https://example.org/empty")));
        assertEquals(2,SocialEvidence.searchHits(rows,5).size());assertEquals(1,SocialEvidence.searchHits(rows,1).size());assertEquals(quote,SocialEvidence.searchHits(rows,5).get(0).quote);assertTrue(SocialEvidence.searchHits(null,5).isEmpty());
    }
    @Test public void groundedSelectionCopiesSourceRatherThanGeneratedQuotation()throws Exception{
        SocialVerdict.Source source=new SocialVerdict.Source("Record","https://example.org/record","Joe Biden was elected president of the United States in 2020.","2020-11-07T12:00:00Z");java.util.List<SocialVerdict.Source> list=java.util.Collections.singletonList(source);
        JSONObject answer=new JSONObject().put("v","true").put("relation","same_event").put("source",0).put("quote","Invented output is not used.");
        assertEquals(source.quote,SocialEvidence.selectedGrounded(answer,list,"Joe Biden was elected in 2020",false,0).quote);
        assertNull(SocialEvidence.selectedGrounded(answer,list,"Current claim",true,java.time.Instant.parse("2026-09-20T00:00:00Z").toEpochMilli()));
        assertNull(SocialEvidence.selectedGrounded(new JSONObject(answer.toString()).put("source",4),list,"Claim",false,0));
        assertNull(SocialEvidence.selectedGrounded(new JSONObject(answer.toString()).put("relation","different_event"),list,"Claim",false,0));
    }
    @Test public void displayedExcerptIsVerbatimAndBounded(){String article="Background sentence. ".repeat(50)+"An exact critical finding concerning lunar water was published.";String excerpt=SocialEvidence.literalExcerpt(article,"critical finding lunar water");assertTrue(article.contains(excerpt));assertTrue(excerpt.length()<=400);assertTrue(excerpt.contains("lunar water"));}
}

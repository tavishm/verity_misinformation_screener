package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SocialVerdictTest {
    @Test public void uncertainLabelUsesResearchLanguage(){assertEquals("Unsure",SocialVerdict.unclear("More evidence is needed.").label());}
    @Test public void cachedNewsBadgeUsesActualPublisher(){
        SocialVerdict.Source source=new SocialVerdict.Source("The Hindu: Literal headline","https://www.thehindu.com/story","Literal headline and summary passage.","2026-09-20T00:00:00Z");
        SocialVerdict verdict=new SocialVerdict("true","news_excerpt","Checked cached news.",Collections.singletonList(source));
        assertEquals("The Hindu",verdict.sourceLabel());
    }
    @Test public void arbitraryArticleTitleDoesNotBecomePublisherBadge(){
        SocialVerdict.Source source=new SocialVerdict.Source("A very long article headline","https://example.org/story","Literal passage long enough to check.","");
        assertEquals("Source",new SocialVerdict("true","sources","Checked source.",Collections.singletonList(source)).sourceLabel());
    }
}

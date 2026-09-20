package org.fairc.forwardcheck;
import org.junit.Test;
import static org.junit.Assert.*;
public class ResearchPolicyTest {
    @Test public void currentRumoursNeedSources(){for(String s:new String[]{"Trump has died","Modi died","A leader was killed today","ट्रम्प की मृत्यु हो गई","The president announced new rules","Read https://example.com/story","Cases went up 50 percent"})assertTrue(s,PhoneResearch.needsLive(s));}
    @Test public void stableFactsCanUseQuickCheck(){assertFalse(PhoneResearch.needsLive("Drinking hot water causes homosexuality."));assertFalse(PhoneResearch.needsLive("The Earth is flat."));}
    @Test public void sourcesRequireHttpsAndNoCredentials(){assertTrue(SourceVerifier.publicUrl("https://www.who.int/health"));assertFalse(SourceVerifier.publicUrl("http://www.who.int/"));assertFalse(SourceVerifier.publicUrl("https://user:pass@example.com"));assertFalse(SourceVerifier.publicUrl("file:///etc/passwd"));}
    @Test public void namesNormalizedWithoutBroadSubstringMatching(){assertEquals("mom",ChosenContacts.normalize(" Mom "));assertNotEquals(ChosenContacts.normalize("Mom"),ChosenContacts.normalize("Mom's group"));}
}

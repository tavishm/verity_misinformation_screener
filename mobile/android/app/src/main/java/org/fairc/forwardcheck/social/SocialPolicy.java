package org.fairc.forwardcheck.social;

import java.util.regex.Pattern;

/** Current-news and exact-cache policy shared by tests and the phone client. */
public final class SocialPolicy {
    public static final String MODEL="qwen/qwen3.7-flash";
    public static final String RESEARCH_MODEL="qwen/qwen3.7-flash";
    public static final int MAX_ACTIVE_CHECKS=4;
    public static final long QUICK_VISIBLE_BUDGET_MS=1300, STABLE_TTL_MS=6*60*60_000L, LIVE_TTL_MS=60_000L;
    private static final Pattern LIVE=Pattern.compile("(?iu)https?://|www\\.|\\b(?:today|yesterday|tomorrow|tonight|current|currently|latest|breaking|just|now|trump|modi|biden|president|minister|election|died|dead|death|killed|arrested|resigned|banned|announced|court|government|price|stock|rupees|dollars|researchers|new study)\\b|\\b20[2-9]\\d\\b|\\d+\\s*(?:%|percent)|आज|मृत्यु|मौत|निधन|मोदी|ट्रम्प");
    public static boolean live(String text){return LIVE.matcher(text).find();}
    // Needing research is different from needing a publication from today.
    // Named people, historical years and percentages alone do not require one.
    private static final Pattern FRESH=Pattern.compile("(?iu)\\b(?:today|yesterday|tomorrow|tonight|current|currently|latest|breaking|just|now|died|dead|death|killed|arrested|resigned|banned|announced|prices?|stocks?)\\b|\\bis (?:the |a )?(?:president|prime minister|alive|dead)\\b|आज|मृत्यु|मौत|निधन");
    public static boolean freshEvidence(String text){return FRESH.matcher(text).find();}
    private static final Pattern CURRENT_NOW=Pattern.compile("(?iu)\\b(?:today|tonight|currently|current|latest|breaking|just|now|prices?|stocks?)\\b|\\bis (?:the |a )?(?:president|prime minister|alive|dead)\\b|\\bhas (?:just )?died\\b|आज|अभी");
    // Yesterday's report remains evidence for an unqualified ongoing story.
    // Explicit current status still needs a source from the last 24 hours;
    // source age never replaces the model's exact event/date comparison.
    public static long sourceAgeWindow(String text){return CURRENT_NOW.matcher(text).find()?86_400_000L:172_800_000L;}
    public static boolean quickEligible(SocialPost post){return !post.media&&post.text.length()<=500&&!live(post.text);}
    // This local classifier was trained for forwarded messages, not headline
    // fragments. On native news cards its confident personal predictions can
    // be wrong; preserve those cards for the cheap semantic opinion gate.
    // The forwarded-message model can be overconfident on headline fragments.
    // Its veto is limited to short, complete personal phrases; mixed claims
    // still reach the combined factual screen.
    public static boolean clearPersonal(String text){return text.trim().matches("(?iu)(?:happy (?:diwali|birthday|new year|holi)|i (?:love|hate|like|dislike) (?:this|that|it)|this is (?:funny|hilarious|beautiful|boring)|good (?:morning|night|evening))[.! ]*");}
    public static boolean localOpinionGate(SocialPost post){return !SocialExtractor.NEWS.equals(post.app);}
    /** X hides long posts even when a whole independent sentence is visible.
     * Such checks are explicitly labelled as visible text, never the full post. */
    public static boolean visibleTextScope(SocialPost post){return SocialExtractor.X.equals(post.app)&&!post.completeVisibleText&&post.text.matches("(?is).*\\b(?:Show more|Read more)$")&&java.util.regex.Pattern.compile("[.!?।](?:\\s|$)").matcher(post.text).find();}
    public static boolean visualClaim(String text){return Pattern.compile("(?iu)\\b(?:this|these|the attached|the above|the below)\\s+(?:(?:viral|old|new|real|fake)\\s+)?(?:photo(?:graph)?s?|pictures?|images?|videos?|footage|clip)\\b|\\b(?:photo|picture|image|video|footage|clip)\\s+(?:shows?|is from|was (?:taken|filmed|recorded)|is (?:real|fake|edited|AI.generated))\\b|यह\\s+(?:तस्वीर|फोटो|वीडियो)").matcher(text).find();}
    public static boolean decisiveQuick(String verdict,double confidence,boolean current,SocialPost post){
        return quickEligible(post)&&!current&&confidence>=.95&&confidence<=1&&(verdict.equals("true")||verdict.equals("false"));
    }
    public static long ttl(SocialPost post){return live(post.text)?LIVE_TTL_MS:STABLE_TTL_MS;}
}

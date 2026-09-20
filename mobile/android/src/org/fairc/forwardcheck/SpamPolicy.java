package org.fairc.forwardcheck;

import java.util.regex.Pattern;

/** Interprets local spam scores and narrow, explainable scam-request patterns. */
final class SpamPolicy {
    private static boolean has(String pattern, String text) { return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find(); }
    private static boolean caution(String value) {
        return has("\\b(?:never|do not|don't|beware|avoid|warning)\\b|\\b(?:scam|fraud)\\b|(?:सावधान|कभी.*नहीं|मत (?:भेजें|बताएं|बताएँ|साझा)|धोखाधड़ी|धोखा)|\\b(?:mat|nahi|nahin)\\s+(?:bhej\\w*|bata\\w*|de\\w*|share|pay|bhar\\w*)\\b|\\b(?:dhokha|dhokhe)\\b",value);
    }

    static String reason(String text, double score, double threshold) {
        String value = text == null ? "" : text;
        boolean caution = caution(value);
        for (String clause : value.split("[.!?।\\n]+")) {
            // Advice about a scam is not a request to carry one out.
            if (caution(clause)) continue;
            if (has("(?:\\b(?:bank|banking)\\s+(?:details|information)\\b|बैंक.{0,15}(?:जानकारी|विवरण))", clause) &&
                    has("\\b(?:send|share|provide|tell)\\b|भेज|बताएं|बताएँ|साझा", clause) &&
                    has("\\b(?:urgent|urgently|immediately|now)\\b|तुरंत|तुरन्त|अभी", clause))
                return "It pressures you to share private banking information.";
            if (has("\\b(?:send|share|tell|give|provide|forward|reply with)\\b.{0,65}\\b(?:otp|one.time.password|password|passcode|(?:upi |atm |bank )?pin|cvv)\\b", clause) ||
                    has("(?:ओटीपी|ओ टी पी|पासवर्ड|पिन|OTP).{0,45}(?:भेजें|भेजो|बताएं|बताएँ|बताओ|शेयर करें|साझा करें)", clause) ||
                    has("\\b(?:otp|password|passcode|pin|cvv)\\b.{0,60}\\b(?:bhej\\w*|bata(?:o|iye|ein|na)|share\\s+karo|de\\s+do)\\b",clause))
                return "It asks you to share a private code or password.";
            if (has("(?:\\b(?:won|winner|prize|lottery|reward|job|recruitment|inaam|inam|naukri)\\b|लॉटरी|इनाम|नौकरी)", clause) &&
                    has("(?:\\b(?:pay|deposit|send money|processing fee|registration fees?|advance fee|fees?\\s+bhar\\w*)\\b|शुल्क|फीस|जमा करें|पैसे भेज)", clause))
                return "It asks for money to receive a prize, reward or job.";
            if(has("\\b(?:double|guaranteed|no risk|risk.free|bina risk|doguna|duguna)\\b|दोगुना|बिना जोखिम",clause) && has("\\b(?:money|funds|profit|paisa|paise|investment)\\b|पैस|मुनाफा",clause))
                return "It promises money without a realistic explanation of the risk.";
        }
        if (!caution && has("\\b(?:you (?:have )?won|you (?:are|have been) (?:a |the )?(?:winner|selected))\\b", value) &&
                has("\\b(?:cash|prize|lottery|reward)\\b", value) && has("\\b(?:claim|call|reply|click|send|pay)\\b", value))
            return "It promises you an unexpected prize and asks you to claim it.";
        if (!caution && has("(?:https?://|www\\.)", value) &&
                has("\\b(?:account|bank|kyc|electricity|card)\\b|खाता|केवाईसी", value) &&
                has("\\b(?:suspend|blocked|block|disconnect|deactivat|expire)|बंद|ब्लॉक", value) &&
                has("\\b(?:click|tap|verify|update|pay|urgent|immediately)\\b|तुरंत|क्लिक", value))
            return "It pressures you to use a link to avoid losing account access.";
        if (!caution && has("(?:scan|स्कैन).{0,35}(?:qr|क्यूआर)", value) &&
                has("(?:receive|collect|refund|cashback|पाने|प्राप्त)", value))
            return "It asks you to scan a payment code to receive money.";
        // The old SMS corpus is broad spam, not a fraud ground-truth dataset.
        // Require commercial language too; don't call ordinary claims a scam.
        if (!caution && score >= threshold && has("\\b(?:free|win|won|prize|claim|cash|offer|reward|urgent|subscribe|bonus|credit|loan|investment|profit|earn|lottery|congratulations)\\b|लॉटरी|इनाम|मुफ्त|कमाएं", value))
            return "Its wording resembles unsolicited offers in the local spam model.";
        return "";
    }
}

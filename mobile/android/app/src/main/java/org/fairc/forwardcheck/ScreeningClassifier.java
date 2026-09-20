package org.fairc.forwardcheck;

import android.content.Context;
import java.util.regex.Pattern;

/** One multilingual routing model regardless of the language of the app controls. */
final class ScreeningClassifier {
    private static final Pattern LINK=Pattern.compile("(?iu)(?:https?://|www\\.)\\S+");
    // Recall guard for explicit public-health causal claims. This never decides
    // truth and is not used to suppress messages the contextual model offers.
    private static final Pattern HEALTH=Pattern.compile("(?iu)\\b(?:vaccine|vaccines|cancer|diabetes|vitamins?|brain|virus|viruses|dengue|teeka|bimaari|dimaag|dil|paudhon|machhar)\\b|टीक|कैंसर|मधुमेह|विटामिन|दिमाग|मस्तिष्क|बीमारी|वायरस|डेंगू|कीटनाशक");
    private static final Pattern CAUSAL=Pattern.compile("(?iu)\\b(?:causes?|cures?|kills?|prevents?|means|never|all|every|se|nahi|nahin|bachata|phailaate)\\b|से|नहीं|कभी|सभी|हर|ज़रूरत|जरूरत");
    private ScreeningClassifier() {}

    static LocalClassifier.Decision classify(Context context,String text) {
        String message=LocalClassifier.limitCodePoints(text==null?"":text).trim();
        if(message.isEmpty())return new LocalClassifier.Decision("personal_skip","No message","Nothing to screen.",1);
        // Explicit credential requests retain their independently tested warning.
        // Ordinary discussion of a scam does not satisfy this narrow policy.
        String warning=SpamPolicy.reason(message,0,1);
        if(!warning.isEmpty())return new LocalClassifier.Decision("spam_warning","Possible scam",warning,1);
        try {
            LocalClassifier.Decision decision=ContextGate.get(context).classify(message);
            if("spam_warning".equals(decision.action)){
                // Spam datasets contain many links, but a link alone is not a
                // reason to warn someone about a scam. Require risk wording.
                String reason=SpamPolicy.reason(message,decision.score,.9);
                if(!reason.isEmpty())return new LocalClassifier.Decision("spam_warning","Possible scam",reason,decision.score);
                return new LocalClassifier.Decision(LINK.matcher(message).find()?"offer_link_check":"factual_offer","Worth checking","Ask before checking this message.",decision.score);
            }
            if(LINK.matcher(message).find())return new LocalClassifier.Decision("offer_link_check","Link","Ask before opening this link.",1);
            if("personal_skip".equals(decision.action) && HEALTH.matcher(message).find() && CAUSAL.matcher(message).find())return new LocalClassifier.Decision("factual_offer","Health claim","Ask before checking a health claim.",decision.score);
            return decision;
        } catch(Exception unavailable) {
            // The previous local gate remains a recovery path if loading fails.
            // Neither path sends the message online.
            try{return LocalClassifier.classify(context,message);}
            catch(RuntimeException failed){return new LocalClassifier.Decision("factual_offer","Worth checking","Ask before checking this message.",0);}
        }
    }
}

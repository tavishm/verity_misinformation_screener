package org.fairc.forwardcheck;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Local-only message routing. A score is check-worthiness, never truth. */
public final class LocalClassifier {
    public static final class Decision {
        public final String action;
        public final String label;
        public final String reason;
        public final double score;

        Decision(String action, String label, String reason, double score) {
            this.action = action;
            this.label = label;
            this.reason = reason;
            this.score = score;
        }
    }

    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9.-]+\\.(?:com|org|net|in|io|co)\\b");
    static final int MAX_INPUT_CODEPOINTS = 5000;
    private static final Pattern SOCIAL_ONLY = Pattern.compile(
            "(?i)^\\s*(?:(?:hi|hello|hey|good\\s+(?:morning|evening|night)|thanks?|thank\\s+you)|(?:happy\\s+(?:diwali|deepavali|holi|eid|birthday|new\\s+year))|(?:diwali|deepavali|eid)\\s+mubarak|(?:i\\s+)?love\\s+you|(?:i(?:'m|\\s+am)\\s+)?proud\\s+of\\s+(?:my\\s+|our\\s+)?(?:kids?|children|you)|(?:so\\s+)?proud\\s+of\\s+(?:my|our)\\s+(?:daughter|son|child|kid)\\s+for\\s+(?:graduating|their\\s+(?:graduation|birthday)|turning\\s+\\d+)(?:\\s+today)?|नमस्ते|हाय|धन्यवाद|(?:शुभ\\s+)?(?:दीवाली|दिवाली)\\s*(?:मुबारक|की\\s+शुभकामनाएं)?|मुझे\\s+तुमसे\\s+प्यार\\s+है|(?:मेरे|हमारे)\\s+बच्चों\\s+पर\\s+गर्व\\s+है|(?:mujhe\\s+)?tumse\\s+pya+r\\s+hai|(?:mere\\s+)?bac+hon\\s+par\\s+garv\\s+hai|मुझे\\s+(?:बुखार|ज़ुकाम|जुकाम)\\s+है)[!.।\\s]*$");
    private static final Pattern PRIVATE = Pattern.compile(
            "(?i)(?:\\b(?:otp|password|passcode|pin)\\b|[\\w.+-]+@[\\w.-]+\\.\\w+|(?:\\+?\\d[\\d -]{7,}\\d)|\\b(?:call|text|meet|pick me up|i am home|i'm home|where are you)\\b|(?:मुझे फोन|कॉल करो|मैं घर|कहाँ हो|ओटीपी|पासवर्ड))");
    private static final Pattern PRIVATE_ONLY = Pattern.compile(
            "(?i)^\\s*(?:(?:my\\s+)?(?:otp|password|passcode|pin)\\D*\\d+|(?:can\\s+you\\s+)?(?:call|text|meet|pick\\s+me\\s+up)[^.!?।]*|(?:i\\s+am|i'm)\\s+home|where\\s+are\\s+you|[\\w.+-]+@[\\w.-]+\\.\\w+|(?:मुझे\\s+फोन|कॉल\\s+करो|मैं\\s+घर|कहाँ\\s+हो|ओटीपी|पासवर्ड)[^.!?।]*)[.!?।\\s]*$");
    private static final Pattern OPINION = Pattern.compile(
            "(?i)^\\s*(?:i\\s+(?:think|feel|believe|love|hate|prefer)|in my opinion|मुझे लगता है|मेरे विचार|मुझे पसंद|मुझे नफरत)");
    private static final Pattern HINDI_FACT = Pattern.compile(
            "(?:सरकार|रिपोर्ट|चुनाव|अदालत|मंत्रालय|प्रतिशत|करोड़|लाख|घोषणा|कानून|आंकड़े|दावा).*(?:है|हैं|था|थी|हुआ|किया)");
    private static final Pattern ASSERTION_CUE = Pattern.compile(
            "(?i)(?:\\b(?:cause[sd]?|cure[sd]?|kill(?:s|ed)?|prevent[sd]?|increase[sd]?|reduce[sd]?|announc(?:e[sd]?|ed)|reported?|claim(?:s|ed)?|result(?:s|ed)?\\s+in)\\b|(?:कारण|इलाज|ठीक करता|ठीक हो जाता|मारता|मर (?:जाता|जाती|जाते)|रोकता|बढ़ाता|घटाता|घोषणा|प्रतिशत|करोड़|लाख)|खाने से.{0,80}(?:मर|होता|होती|होते)|से.{1,80}होता है)");
    private static final Pattern NUMBER_CUE = Pattern.compile(
            "(?i)\\b\\d+(?:[.,]\\d+)?\\s*(?:%|percent|million|billion|lakh|crore)?\\b");
    private static final Pattern CLAUSE = Pattern.compile(
            "(?i)[.!?।]+|\\b(?:but|however|although|लेकिन|मगर|पर)\\b");

    private static volatile Model cached;

    private LocalClassifier() {}

    public static Decision classify(Context context, String text) {
        String raw = limitCodePoints(text == null ? "" : text);
        String value = normalize(raw);
        if (value.isEmpty()) return new Decision("personal_skip", "No message", "Nothing was sent off-device.", 0);
        Decision spam = SpamClassifier.classify(context, raw);
        if (spam != null) return spam;
        if (URL.matcher(value).find()) return new Decision("offer_link_check", "Link detected", "Offer a separate link check; do not fetch until the user agrees.", 1);
        if (SOCIAL_ONLY.matcher(value).find()) return new Decision("personal_skip", "Personal or greeting", "A greeting does not need a fact check.", 0);
        boolean semanticCue = ASSERTION_CUE.matcher(value).find() || HINDI_FACT.matcher(value).find();
        Model model = model(context.getApplicationContext());
        double score = model.embedding.score(raw);
        String[] parts=CLAUSE.split(raw); int checked=0;
        for (String part : parts) {
            if (!part.trim().isEmpty() && checked++<12) score = Math.max(score, model.embedding.score(part.trim()));
        }
        boolean factualCue = semanticCue || NUMBER_CUE.matcher(value).find();
        if (PRIVATE_ONLY.matcher(value).find()) return new Decision("personal_skip", "Likely private", "Keep personal details on the phone and skip upload.", 0);
        if (score >= model.factualThreshold) return new Decision("factual_offer", "Factual claim", "Offer a fact check; upload only after Yes.", score);
        if (score >= model.uncertainThreshold || factualCue) return new Decision("factual_offer", "Possibly factual", "Ask whether to check; upload only after Yes.", score);
        if (parts.length>12) return new Decision("factual_offer", "Possibly factual", "Long message: ask whether to check; upload only after Yes.", score);
        if (OPINION.matcher(value).find()) return new Decision("opinion_skip", "Opinion", "Personal opinions are not truth-checked.", score);
        return new Decision("opinion_skip", "No check-worthy claim detected", "Keep the message local and skip by default.", score);
    }

    private static Model model(Context context) {
        Model current = cached;
        if (current != null) return current;
        synchronized (LocalClassifier.class) {
            if (cached != null) return cached;
            try (InputStream stream = context.getAssets().open("forward_classifier.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONObject root = new JSONObject(bytes.toString("UTF-8"));
                int dimensions = root.getInt("dimensions");
                JSONArray values = root.getJSONArray("weights");
                if (values.length() != dimensions) throw new IllegalStateException("Classifier dimensions mismatch");
                double[] weights = new double[dimensions];
                for (int i = 0; i < dimensions; i++) weights[i] = values.getDouble(i);
                JSONObject thresholds = root.getJSONObject("thresholds");
                TinyEmbedding embedding = new TinyEmbedding(context.getAssets(), weights, root.getDouble("intercept"), root.getInt("max_tokens"));
                cached = new Model(dimensions, root.getDouble("intercept"), weights,
                        thresholds.getDouble("factual_offer"), thresholds.getDouble("uncertain_offer"), embedding);
                return cached;
            } catch (Exception error) {
                throw new IllegalStateException("Unable to load on-device classifier", error);
            }
        }
    }

    static String normalize(String text) {
        String source = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder();
        boolean gap = false;
        for (int offset = 0; offset < source.length();) {
            int cp = source.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                gap = output.length() > 0;
            } else {
                if (gap) output.append(' ');
                output.appendCodePoint(cp); gap = false;
            }
        }
        return output.toString();
    }

    private static double score(Model model, String text) {
        Set<Integer> indices = featureIndices(text, model.dimensions);
        double value = model.intercept;
        for (int index : indices) value += model.weights[index];
        value = Math.max(-35, Math.min(35, value));
        return 1.0 / (1.0 + Math.exp(-value));
    }

    static Set<Integer> featureIndices(String text, int dimensions) {
        String value = normalize(limitCodePoints(text));
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) word.appendCodePoint(cp);
            else if (word.length() > 0) { words.add(word.toString()); word.setLength(0); }
        }
        if (word.length() > 0) words.add(word.toString());
        Set<Integer> output = new HashSet<>();
        for (int size = 1; size <= 2; size++) {
            for (int i = 0; i + size <= words.size(); i++) {
                StringBuilder feature = new StringBuilder("w:");
                for (int j = 0; j < size; j++) {
                    if (j > 0) feature.append('\u001f');
                    feature.append(words.get(i + j));
                }
                output.add(index(feature.toString(), dimensions));
            }
        }
        int[] chars = (" " + value + " ").codePoints().toArray();
        for (int size = 3; size <= 5; size++) {
            for (int i = 0; i + size <= chars.length; i++) {
                output.add(index("c" + size + ":" + new String(chars, i, size), dimensions));
            }
        }
        return output;
    }

    static String limitCodePoints(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        if (count <= MAX_INPUT_CODEPOINTS) return text;
        return text.substring(0, text.offsetByCodePoints(0, MAX_INPUT_CODEPOINTS));
    }

    private static int index(String value, int dimensions) {
        long hash = 2166136261L;
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= item & 0xffL;
            hash = (hash * 16777619L) & 0xffffffffL;
        }
        return (int) (hash % dimensions);
    }

    private static final class Model {
        final int dimensions;
        final double intercept;
        final double[] weights;
        final double factualThreshold;
        final double uncertainThreshold;
        final TinyEmbedding embedding;
        Model(int dimensions, double intercept, double[] weights,
              double factualThreshold, double uncertainThreshold, TinyEmbedding embedding) {
            this.dimensions = dimensions; this.intercept = intercept; this.weights = weights;
            this.factualThreshold = factualThreshold; this.uncertainThreshold = uncertainThreshold; this.embedding = embedding;
        }
    }
}

package org.fairc.forwardcheck;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;

/** Frozen language embeddings plus a 129-parameter head trained on public SMS. */
final class SpamClassifier {
    private static volatile SpamClassifier cached;
    private final TinyEmbedding embedding;
    private final double threshold;
    private SpamClassifier(Context context) throws Exception {
        JSONObject head;
        try (InputStream input = context.getAssets().open("spam_classifier.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            head = new JSONObject(bytes.toString("UTF-8"));
        }
        threshold = head.getDouble("threshold");
        embedding = new TinyEmbedding(context.getAssets().open("spam_embedding_projection.bin"),
                context.getAssets().open("forward_embedding_vocab.txt"), new double[128], head.getDouble("intercept"), head.getInt("max_tokens"));
    }
    static LocalClassifier.Decision classify(Context context, String text) {
        String reason = SpamPolicy.reason(text, 0, 1);
        double score = 0;
        if (reason.isEmpty()) {
            try {
                SpamClassifier current = cached;
                if (current == null) synchronized (SpamClassifier.class) {
                    if (cached == null) cached = new SpamClassifier(context.getApplicationContext());
                    current = cached;
                }
                score = current.embedding.score(text);
                reason = SpamPolicy.reason(text, score, current.threshold);
            } catch (Exception unavailable) { return null; }
        }
        return reason.isEmpty() ? null : new LocalClassifier.Decision("spam_warning", "Likely spam or scam", reason, score);
    }
}

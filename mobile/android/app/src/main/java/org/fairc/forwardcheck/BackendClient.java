package org.fairc.forwardcheck;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal local API client. It has no analytics, link prefetch, or message cache. */
public final class BackendClient {
    public interface Callback<T> { void success(T value); void failure(String message); }
    public static final class Citation {
        public final String title, url, quote, publishedAt;
        Citation(String title, String url, String quote, String publishedAt) { this.title=title; this.url=url; this.quote=quote; this.publishedAt=publishedAt; }
    }
    public static final class Claim {
        public final String text, verdict, explanation;
        public final List<Citation> citations;
        Claim(String text, String verdict, String explanation, List<Citation> citations) { this.text=text; this.verdict=verdict; this.explanation=explanation; this.citations=citations; }
    }
    public static final class Job {
        public final String status, label, scope, stage, error, provider, cost, basis;
        public final int rating;
        public final List<Claim> claims;
        Job(String status, String label, int rating, String scope, String stage, String error,
            String provider, String cost, String basis, List<Claim> claims) {
            this.status=status; this.label=label; this.rating=rating; this.scope=scope; this.stage=stage; this.error=error;
            this.provider=provider; this.cost=cost; this.basis=basis; this.claims=claims;
        }
    }
    private final AppPrefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    public BackendClient(AppPrefs prefs) { this.prefs = prefs; }
    public void dispose() { /* Application-owned jobs survive Activity recreation. */ }
    public void check(String text, boolean consent, boolean force, Callback<String> callback) {
        try { String id = PhoneResearch.get(prefs.context).start(text, consent, force); main.post(() -> callback.success(id)); }
        catch (Exception error) { fail(callback, error.getMessage()); }
    }

    public void job(String id, Callback<Job> callback) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) { fail(callback, "Invalid check reference."); return; }
        request("/api/mobile/jobs/" + id, null, true, new Callback<JSONObject>() {
            @Override public void success(JSONObject response) {
                JSONObject result = response.optJSONObject("result");
                String label = result == null ? "" : result.optString("label", "");
                int rating = result == null ? 0 : result.optInt("rating", 0);
                String scope = result == null ? "" : result.optString("scope", "");
                JSONObject research = result == null ? null : result.optJSONObject("research");
                if (research == null) research = response.optJSONObject("research");
                String provider = firstNonEmpty(
                        result == null ? "" : result.optString("provider", ""),
                        result == null ? "" : result.optString("cloud_provider", ""),
                        research == null ? "" : research.optString("provider", ""),
                        response.optString("provider", ""));
                String cost = costMetadata(result, research, response);
                List<Claim> claims = new ArrayList<>();
                if (result != null) {
                    JSONArray rows = result.optJSONArray("claims");
                    for (int i = 0; rows != null && i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                        List<Citation> citations = new ArrayList<>(); JSONArray evidence = row.optJSONArray("evidence");
                        for (int j = 0; evidence != null && j < evidence.length(); j++) {
                            JSONObject source = evidence.optJSONObject(j); if (source == null) continue;
                            String url = source.optString("source_url", source.optString("sourceURL", ""));
                            citations.add(new Citation(source.optString("title", "Source"), url,
                                    source.optString("quote", source.optString("text", "")), source.optString("published_at", source.optString("published_at_raw", ""))));
                        }
                        claims.add(new Claim(row.optString("text", ""), row.optString("verdict", "insufficient_evidence"), row.optString("explanation", "No explanation was returned."), citations));
                    }
                }
                callback.success(new Job(response.optString("status", "pending"), label, rating, scope,
                        response.optString("stage", ""), response.optString("error", ""), provider, cost, result == null ? "" : result.optString("assessment_basis", ""), claims));
            }
            @Override public void failure(String message) { fail(callback, message); }
        });
    }
    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
    private static String costMetadata(JSONObject... objects) {
        for (JSONObject object : objects) {
            if (object == null) continue;
            Object usd = object.opt("cost_usd");
            if (usd == null || usd == JSONObject.NULL) usd = object.opt("estimated_cost_usd");
            if (usd != null && usd != JSONObject.NULL) return "USD " + String.valueOf(usd);
            Object raw = object.opt("cost");
            if (raw != null && raw != JSONObject.NULL && !String.valueOf(raw).trim().isEmpty()) return String.valueOf(raw).trim();
        }
        return "";
    }

    private void request(String path, JSONObject body, boolean authenticated, Callback<JSONObject> callback) {
        try { JSONObject response = PhoneResearch.get(prefs.context).job(path.substring(path.lastIndexOf('/') + 1)); main.post(() -> callback.success(response)); }
        catch (Exception error) { fail(callback, "This check was interrupted. Please try again."); }
    }
    private <T> void fail(Callback<T> callback, String message) { main.post(() -> callback.failure(message)); }
}

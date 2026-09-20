package org.fairc.forwardcheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

/** One decision per screen. General-knowledge answers are identified as AI checks. */
public final class ConsentActivity extends Activity {
    private String message, source, jobId, messageFingerprint, imageNote;
    private boolean imageNoText;
    private AppPrefs prefs;
    private BackendClient client;
    private Ui ui;
    private TextView status;
    private LinearLayout box;
    private LocalClassifier.Decision localDecision;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long started;
    private boolean visible, checking, polling;
    private volatile boolean destroyed;
    private final java.util.concurrent.ExecutorService localWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        prefs = new AppPrefs(this); client = new BackendClient(prefs); ui = new Ui(this);
        message = state != null ? state.getString("message") : getIntent().getStringExtra(MainActivity.EXTRA_TEXT); source = getIntent().getStringExtra(MainActivity.EXTRA_SOURCE);
        messageFingerprint = getIntent().getStringExtra("message_fingerprint");
        imageNote = getIntent().getStringExtra("image_note"); imageNoText = getIntent().getBooleanExtra("image_no_text", false);
        jobId = state != null ? state.getString("job_id") : getIntent().getStringExtra("job_id");
        started = state != null ? state.getLong("started", SystemClock.uptimeMillis()) : SystemClock.uptimeMillis();
        if (jobId != null) { if (message == null) message = PhoneResearch.get(this).text(jobId); checking = true; loading(); return; }
        if (message == null || message.trim().isEmpty()) { finish(); return; }
        newScreen(); box.addView(ui.title("One moment…"));
        final String selected = message;
        localWorker.execute(() -> {
            LocalClassifier.Decision decision = null;
            try { decision = ScreeningClassifier.classify(getApplicationContext(), selected); } catch (RuntimeException ignored) {}
            final LocalClassifier.Decision result = decision;
            runOnUiThread(() -> { if (!destroyed) { localDecision = result; showReview(); } });
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("message", message); state.putString("job_id", jobId); state.putLong("started", started); }
    private void loading() { newScreen(); box.addView(new ProgressBar(this)); status = ui.title("Checking…"); box.addView(status); }
    @Override protected void onResume() { super.onResume(); visible = true; if (jobId != null) poll(jobId); }
    @Override protected void onPause() { visible = false; handler.removeCallbacksAndMessages(null); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; localWorker.shutdownNow(); handler.removeCallbacksAndMessages(null); client.dispose(); message = null; super.onDestroy(); }
    private void newScreen() { box = ui.column(); setContentView(ui.screen(box)); }
    private boolean spam() { return localDecision != null && "spam_warning".equals(localDecision.action); }
    private boolean personal() { return localDecision != null && ("personal_skip".equals(localDecision.action) || "opinion_skip".equals(localDecision.action)); }

    private void showReview() {
        newScreen();
        if (imageNoText) {
            box.addView(ui.mark("?", Ui.AMBER)); box.addView(ui.title("Could not read any words"));
            box.addView(ui.text(imageNote == null || imageNote.isEmpty() ? "Share a clearer picture to Verity." : imageNote, 21));
            Button done = ui.button("Done", true); done.setOnClickListener(v -> closeReview()); box.addView(done); return;
        }
        if (spam()) {
            box.addView(ui.mark("!", Ui.AMBER));
            box.addView(ui.title("This may be a scam"));
            box.addView(ui.text("Please ignore it.", 23));
            Button done = ui.button("Close", true); done.setOnClickListener(v -> closeReview()); box.addView(done);
            Button why = ui.link("Why?"); why.setOnClickListener(v -> {
                LinearLayout details = ui.column();
                details.addView(ui.text(localDecision.reason, 20)); ui.preview(details, message);
                details.addView(ui.note("This was checked on your phone."));
                I18n.dialog(this).setTitle("About this warning").setView(ui.detailsScroll(details)).setPositiveButton("OK", null).show();
            }); box.addView(why);
            return;
        }
        if (personal()) {
            box.addView(ui.mark("✓", Ui.GREEN)); box.addView(ui.title("Nothing to check"));
            box.addView(ui.text("This looks like a personal message.", 22));
            Button done = ui.button("Done", true); done.setOnClickListener(v -> closeReview()); box.addView(done);
            Button anyway = ui.link("Check anyway"); anyway.setOnClickListener(v -> showConsent()); box.addView(anyway); return;
        }
        showConsent();
    }
    private void showConsent() {
        newScreen(); box.addView(ui.title("Is this true?")); ui.preview(box, message);
        box.addView(ui.note("image".equals(source) ? "Only these words are sent. The picture stays on your phone." : "Only this message is sent for checking."));
        Button yes = ui.button("Yes, check", true); yes.setOnClickListener(v -> submit(false)); yes.setEnabled(prefs.paired()); box.addView(yes);
        if (!prefs.paired()) box.addView(ui.note("Add the checking key in Settings first."));
        Button no = ui.link("Not now"); no.setOnClickListener(v -> closeReview()); box.addView(no);
    }
    private void submit(boolean forceResearch) {
        if (checking) return;
        ForwardAccessibilityService.acknowledgeMessageInstance(this, messageFingerprint);
        checking = true; started = SystemClock.uptimeMillis(); polling = false;
        newScreen();
        ProgressBar spinner = new ProgressBar(this); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); p.gravity = Gravity.CENTER_HORIZONTAL; p.topMargin = ui.dp(70); p.bottomMargin = ui.dp(24); box.addView(spinner, p);
        status = ui.title("Checking…"); status.setGravity(Gravity.CENTER); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); box.addView(status);
        client.check(message, true, forceResearch, new BackendClient.Callback<String>() {
            @Override public void success(String job) { if (destroyed) return; jobId = job; if (visible) poll(job); }
            @Override public void failure(String error) { if (!destroyed) showFailure(error); }
        });
    }
    private void showFailure(String error) {
        checking = false; newScreen(); box.addView(ui.mark("!", Ui.AMBER));
        box.addView(ui.title("Could not check"));
        box.addView(ui.text(error == null || error.isEmpty() ? "Please try again." : error, 22));
        Button retry = ui.button("Try again", true); retry.setOnClickListener(v -> submit(false)); box.addView(retry);
        Button done = ui.link("Close"); done.setOnClickListener(v -> closeReview()); box.addView(done);
        if (error != null && error.contains("Settings")) box.addView(ui.note(error));
    }
    private void poll(String job) {
        if (!visible || job == null || polling) return;
        polling = true;
        client.job(job, new BackendClient.Callback<BackendClient.Job>() {
            @Override public void success(BackendClient.Job value) {
                polling = false; if (!visible) return;
                if ("pending".equals(value.status) && SystemClock.uptimeMillis() - started < 180000) {
                    long age = SystemClock.uptimeMillis() - started;
                    status.setText(I18n.text(ConsentActivity.this,age < 2500 ? "Checking…" : value.stage.contains("link") ? "Reading the link…" : "Checking online…"));
                    handler.postDelayed(() -> poll(job), age < 5000 ? 200 : 1000); return;
                }
                if ("complete".equals(value.status)) render(value); else showFailure(value.error);
            }
            @Override public void failure(String error) { polling = false; if (!destroyed) showFailure(error); }
        });
    }
    private void render(BackendClient.Job value) {
        checking = false; newScreen();
        boolean quick = "quick_model".equals(value.basis);
        boolean falseClaim = false, allSupported = !value.claims.isEmpty();
        for (BackendClient.Claim claim : value.claims) {
            if ("contradicted".equals(claim.verdict)) falseClaim = true;
            if (!"supported".equals(claim.verdict) || (!quick && claim.citations.isEmpty())) allSupported = false;
        }
        boolean personalResult = "Nothing to check".equals(value.label) || "No factual claim".equals(value.label);
        int color = falseClaim ? Ui.RED : allSupported || personalResult ? Ui.GREEN : Ui.AMBER;
        box.addView(ui.mark(falseClaim ? "×" : allSupported || personalResult ? "✓" : "?", color));
        box.addView(ui.note(quick ? "Quick AI check" : "Online check"));
        TextView title = ui.title(personalResult ? "Nothing to check" : falseClaim ? "Looks false" : allSupported ? "Looks true" : "Do not share yet"); title.setTextColor(color); box.addView(title);
        if (falseClaim) box.addView(ui.text("Please do not forward this.", 22));
        else if (!allSupported && !personalResult) box.addView(ui.text("We could not check this.", 22));
        if (spam()) box.addView(ui.text("This may still be a scam. Please ignore it.", 21));
        Button done = ui.button("Done", true); done.setOnClickListener(v -> closeReview()); box.addView(done);
        Button why = ui.link("Why?"); why.setOnClickListener(v -> showDetails(value, quick)); box.addView(why);
    }
    private void showDetails(BackendClient.Job value, boolean quick) {
        LinearLayout details = ui.column(); ui.preview(details, message);
        if (imageNote != null && !imageNote.isEmpty()) details.addView(ui.text(imageNote, 20));
        if (quick) {
            // A model explanation may overreach the selected claim; don't
            // present it as an established reason or fabricate source passages.
            details.addView(ui.text("This is a quick answer from AI. It can be wrong. No websites were checked.", 20));
            Button online = ui.button("Check online", true);
            AlertDialog dialog = I18n.dialog(this).setTitle("About this check").setView(ui.detailsScroll(details)).setPositiveButton("Close", null).create();
            details.addView(online); online.setOnClickListener(v -> { dialog.dismiss(); submit(true); }); dialog.show(); return;
        }
        for (BackendClient.Claim claim : value.claims) {
            details.addView(ui.rawText(claim.text, 20));
            details.addView(ui.note("contradicted".equals(claim.verdict) ? "Looks false" : "supported".equals(claim.verdict) ? "Looks true" : "Could not check"));
            if(!claim.explanation.isEmpty())details.addView(ui.rawText(claim.explanation,19));
            for (BackendClient.Citation citation : claim.citations) {
                if (!citation.quote.isEmpty()) details.addView(ui.rawText(citation.quote, 19));
                if (safeLink(citation.url)) {
                    Button open = ui.link(citation.title.isEmpty() ? "Read more" : citation.title);
                    open.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(citation.url))); } catch (RuntimeException ignored) { Toast.makeText(this, "Could not open the page.", Toast.LENGTH_SHORT).show(); } }); details.addView(open);
                }
            }
        }
        if (value.claims.isEmpty()) details.addView(ui.text("We could not find a clear answer. This does not mean the message is true.", 20));
        I18n.dialog(this).setTitle("Why?").setView(ui.detailsScroll(details)).setPositiveButton("OK", null).show();
    }
    private boolean safeLink(String value) { try { Uri uri = Uri.parse(value); return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null; } catch (Exception ignored) { return false; } }
    private void closeReview() { ForwardAccessibilityService.acknowledgeMessageInstance(this, messageFingerprint); if (!"manual".equals(source) && !"demo".equals(source)) moveTaskToBack(true); finish(); }
    @Override public void onBackPressed() { closeReview(); }
}

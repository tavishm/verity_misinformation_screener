package org.fairc.forwardcheck;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Small, consistent native controls with large text and touch targets. */
final class Ui {
    static final int INK = 0xff183a32, GREEN = 0xff087568, BACKGROUND = 0xfff3f7f5, SURFACE = 0xffffffff;
    static final int MUTED = 0xff53645f, BORDER = 0xffd5e2dd;
    static final int RED = 0xffb42318, AMBER = 0xff925400;
    final Activity activity;
    Ui(Activity activity) { this.activity = activity; }
    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    ScrollView screen(LinearLayout box) {
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        box.setPadding(dp(22), dp(24), dp(22), dp(28)); scroll.addView(box); return scroll;
    }
    TextView text(String value, int size) { return rawText(I18n.text(activity,value), size); }
    TextView rawText(String value, int size) {
        TextView v = new TextView(activity); v.setText(value); v.setTextSize(size); v.setTextColor(INK);
        v.setLineSpacing(dp(3), 1); v.setPadding(0, dp(6), 0, dp(6)); v.setSaveEnabled(false); return v;
    }
    TextView title(String value) { TextView v = text(value, 31); v.setTypeface(null, Typeface.BOLD); v.setAccessibilityHeading(true); v.setPadding(0, dp(4), 0, dp(12)); return v; }
    TextView note(String value) { TextView v = text(value, 17); v.setTextColor(MUTED); return v; }
    Button button(String label, boolean primary) {
        Button v = new Button(activity); v.setText(I18n.text(activity,label)); v.setAllCaps(false); v.setTextSize(20); v.setMinHeight(dp(62));
        v.setPadding(dp(16), dp(11), dp(16), dp(11)); v.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(9); v.setLayoutParams(p);
        v.setBackground(buttonBackground(primary)); v.setTextColor(buttonText(primary));
        v.setElevation(primary ? dp(2) : 0); v.setStateListAnimator(null);
        return v;
    }
    Button link(String label) {
        Button v = button(label, false); v.setTextSize(18); v.setMinHeight(dp(52)); v.setTypeface(null, Typeface.NORMAL);
        v.setBackground(plainButtonBackground()); v.setTextColor(GREEN); v.setElevation(0); return v;
    }
    Button menuLink(String label) {
        Button v = link(label); v.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); v.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = 0; v.setLayoutParams(p); return v;
    }
    View divider() {
        View line = new View(activity); line.setBackgroundColor(0xffe6eeea);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return line;
    }
    TextView mark(String symbol, int color) {
        TextView v = text(symbol, 42); v.setGravity(Gravity.CENTER); v.setPadding(0, 0, 0, 0); v.setTypeface(null, Typeface.BOLD); v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(72), dp(72)); p.gravity = Gravity.CENTER_HORIZONTAL; p.bottomMargin = dp(8); v.setLayoutParams(p);
        styleMark(v,symbol,color); return v;
    }
    void styleMark(TextView v,String symbol,int color) {
        v.setText(symbol); v.setTextColor(color); GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
        circle.setColor((color & 0xffffff) | 0x18000000); v.setBackground(circle);
    }
    ScrollView detailsScroll(LinearLayout details) {
        ScrollView v = new ScrollView(activity); details.setPadding(dp(22), dp(8), dp(22), dp(16)); v.addView(details); return v;
    }
    LinearLayout card() {
        LinearLayout v = column(); v.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = shape(SURFACE, BORDER); bg.setCornerRadius(dp(20)); v.setBackground(bg); v.setElevation(dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); p.bottomMargin = dp(8); v.setLayoutParams(p); return v;
    }
    void disclosure(LinearLayout parent, String label, LinearLayout details) {
        Button toggle = button(label, false); toggle.setTextSize(18); parent.addView(toggle);
        details.setVisibility(View.GONE); parent.addView(details);
        toggle.setOnClickListener(v -> { boolean open = details.getVisibility() != View.VISIBLE; details.setVisibility(open ? View.VISIBLE : View.GONE); toggle.setText(open ? "Hide details" : label); });
    }
    void preview(LinearLayout parent, String message) {
        LinearLayout card = card(); TextView body = rawText(message, 21); body.setMaxLines(3); body.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(body);
        if (message.length() > 120 || message.contains("\n")) {
            Button more = link("Read more"); more.setTextSize(17); card.addView(more);
            more.setOnClickListener(v -> { boolean expand = body.getMaxLines() == 3; body.setMaxLines(expand ? Integer.MAX_VALUE : 3); body.setEllipsize(expand ? null : android.text.TextUtils.TruncateAt.END); more.setText(I18n.text(activity,expand ? "Show less" : "Read more")); });
        } else body.setMaxLines(Integer.MAX_VALUE);
        parent.addView(card);
    }
    private GradientDrawable shape(int color,int stroke) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(16));
        if(stroke!=0)bg.setStroke(dp(1),stroke); return bg;
    }
    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},shape(primary?0xffa7bab5:0xffeef2f0,primary?0:0xffdce5e1));
        states.addState(new int[]{android.R.attr.state_pressed},shape(primary?0xff065f55:0xffe6efeb,primary?0:BORDER));
        states.addState(new int[]{},shape(primary?GREEN:SURFACE,primary?0:BORDER)); return states;
    }
    private ColorStateList buttonText(boolean primary) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{primary?0xffedf3f1:0xff8a9995,primary?0xffffffff:INK});
    }
    private StateListDrawable plainButtonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},shape(0xffe6efeb,0)); states.addState(new int[]{},shape(0x00ffffff,0)); return states;
    }
}

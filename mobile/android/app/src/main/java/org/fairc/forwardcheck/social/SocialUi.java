package org.fairc.forwardcheck.social;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class SocialUi {
    static final int INK=Color.rgb(24,43,40),GREEN=Color.rgb(15,112,79),RED=Color.rgb(174,43,49),AMBER=Color.rgb(119,79,0);
    final Context c;
    SocialUi(Context c){this.c=c;}
    int dp(float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(INK);t.setPadding(0,dp(6),0,dp(6));return t;}
    TextView title(String s){TextView t=text(s,30);t.setTypeface(null,Typeface.BOLD);return t;}
    LinearLayout column(){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(24),dp(30),dp(24),dp(24));return l;}
    ScrollView screen(LinearLayout l){ScrollView s=new ScrollView(c);s.setFillViewport(true);s.setBackgroundColor(Color.rgb(247,249,247));s.addView(l);s.setFitsSystemWindows(true);return s;}
    Button button(String label,boolean primary){Button b=new Button(c);b.setAllCaps(false);b.setText(label);b.setTextSize(20);b.setTextColor(primary?Color.WHITE:GREEN);b.setMinHeight(dp(58));b.setBackground(round(primary?GREEN:Color.WHITE,dp(14)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);b.setLayoutParams(p);return b;}
    static GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
}

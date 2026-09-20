#!/usr/bin/env python3
"""Build clearly labelled synthetic native feed fixtures for an EMPTY emulator.

Their app IDs exercise the supported accessibility package filters. These are
not the real social apps and must never be installed on a person's phone.
"""
from pathlib import Path
import importlib.util, subprocess

ROOT=Path(__file__).resolve().parents[2]
PROJECT=ROOT/'.cache/social-fixture'

def write(name,text):
    p=PROJECT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)

def main():
    spec=importlib.util.spec_from_file_location('build',ROOT/'mobile/tooling/standalone_build.py');t=importlib.util.module_from_spec(spec);spec.loader.exec_module(t)
    gradle,env=t.prepare()
    write('settings.gradle',(ROOT/'mobile/android/settings.gradle').read_text().replace("'ForwardCheck'","'SyntheticFeedFixtures'"))
    write('build.gradle',(ROOT/'mobile/android/build.gradle').read_text())
    write('gradle.properties','android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1024m\norg.gradle.workers.max=2\n')
    write('app/build.gradle','''plugins { id 'com.android.application' }
android {
 namespace 'org.fairc.socialfixture'
 compileSdk 35
 buildToolsVersion '35.0.0'
 defaultConfig { minSdk 30; targetSdk 35; versionCode 1; versionName 'TEST-FIXTURE' }
 flavorDimensions 'feed'
 productFlavors {
  x { dimension 'feed'; applicationId 'com.twitter.android' }
  reddit { dimension 'feed'; applicationId 'com.reddit.frontpage' }
  news { dimension 'feed'; applicationId 'com.google.android.apps.magazines' }
 }
 compileOptions { sourceCompatibility JavaVersion.VERSION_11; targetCompatibility JavaVersion.VERSION_11 }
}
''')
    write('app/src/main/AndroidManifest.xml','''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="SYNTHETIC FEED — NOT A REAL APP" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name="org.fairc.socialfixture.Feed" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
    write('app/src/main/res/values/ids.xml','''<resources><item name="tweet_row" type="id"/><item name="tweet_text" type="id"/><item name="post_container" type="id"/><item name="post_title" type="id"/><item name="article_card" type="id"/><item name="article_title" type="id"/></resources>''')
    write('app/src/main/java/org/fairc/socialfixture/Feed.java','''package org.fairc.socialfixture;
import android.app.*;import android.os.*;import android.graphics.Color;import android.widget.*;
public final class Feed extends Activity {
 int selected=0;String[] claims={"The Earth is flat.","The Earth goes around the Sun.","Happy Diwali! I am proud of my children.","Trump has died.","The Earth does not go around the Sun."};
 public void onCreate(Bundle b){super.onCreate(b);draw();}
 void draw(){LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(20,60,20,20);root.setFitsSystemWindows(true);root.setBackgroundColor(Color.WHITE);
 TextView notice=new TextView(this);notice.setText("SYNTHETIC FEED FIXTURE\\nNot the real social app");notice.setTextColor(Color.RED);notice.setTextSize(18);root.addView(notice);
 LinearLayout controls=new LinearLayout(this);Button next=new Button(this);next.setText("Next test post");next.setOnClickListener(v->{selected=(selected+1)%claims.length;draw();});controls.addView(next);root.addView(controls);
 ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll);
 int container=getPackageName().equals("com.twitter.android")?R.id.tweet_row:getPackageName().equals("com.reddit.frontpage")?R.id.post_container:R.id.article_card;
 int body=getPackageName().equals("com.twitter.android")?R.id.tweet_text:getPackageName().equals("com.reddit.frontpage")?R.id.post_title:R.id.article_title;
 for(int i=0;i<7;i++){LinearLayout post=new LinearLayout(this);post.setId(container);post.setOrientation(1);post.setPadding(24,50,24,50);TextView text=new TextView(this);text.setId(body);text.setText(i==0?claims[selected]:"This is a personal update about my family number "+i+".");text.setTextSize(26);post.addView(text);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,440);post.setLayoutParams(p);list.addView(post);}
 setContentView(root);}
}''')
    subprocess.run([str(gradle),'--no-daemon','--console=plain',':app:assembleDebug'],cwd=PROJECT,env=env,check=True)
    for apk in (PROJECT/'app/build/outputs/apk').rglob('*.apk'):print(apk)

if __name__=='__main__':main()

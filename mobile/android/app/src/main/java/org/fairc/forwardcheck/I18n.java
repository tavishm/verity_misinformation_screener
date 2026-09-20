package org.fairc.forwardcheck;
import android.content.*;
import android.content.res.Configuration;
import java.util.*;
final class I18n {
private static final Map<String,Integer> IDS=new HashMap<>();
static {
IDS.put("Verity",R.string.ui_0);
IDS.put("Could not open this link. Try pasting the message instead.",R.string.ui_1);
IDS.put("Is this message true?",R.string.ui_2);
IDS.put("Is this true?",R.string.ui_3);
IDS.put("This may be a scam",R.string.ui_4);
IDS.put("Please ignore it.",R.string.ui_5);
IDS.put("Please ignore this message.",R.string.ui_6);
IDS.put("It may be asking for money, passwords or private details. This warning was made on your phone.",R.string.ui_7);
IDS.put("Check",R.string.ui_8);
IDS.put("Yes, check",R.string.ui_9);
IDS.put("Not now",R.string.ui_10);
IDS.put("Ignore",R.string.ui_11);
IDS.put("Why?",R.string.ui_12);
IDS.put("Close",R.string.ui_13);
IDS.put("Done",R.string.ui_14);
IDS.put("Back",R.string.ui_15);
IDS.put("OK",R.string.ui_16);
IDS.put("Cancel",R.string.ui_17);
IDS.put("Save",R.string.ui_18);
IDS.put("Continue",R.string.ui_19);
IDS.put("Checking…",R.string.ui_20);
IDS.put("Checking online…",R.string.ui_21);
IDS.put("Reading the link…",R.string.ui_22);
IDS.put("Reading the picture…",R.string.ui_23);
IDS.put("One moment…",R.string.ui_24);
IDS.put("Looks false",R.string.ui_25);
IDS.put("Looks true",R.string.ui_26);
IDS.put("Do not share yet",R.string.ui_27);
IDS.put("Please do not forward this.",R.string.ui_28);
IDS.put("The claim appears correct.",R.string.ui_29);
IDS.put("We could not find a clear answer.",R.string.ui_30);
IDS.put("We could not check this.",R.string.ui_31);
IDS.put("Could not check",R.string.ui_32);
IDS.put("Please try again.",R.string.ui_33);
IDS.put("Try again",R.string.ui_34);
IDS.put("Quick AI check · can be wrong",R.string.ui_35);
IDS.put("Quick AI check",R.string.ui_36);
IDS.put("Online check",R.string.ui_37);
IDS.put("Checked online",R.string.ui_38);
IDS.put("About this check",R.string.ui_39);
IDS.put("About this warning",R.string.ui_40);
IDS.put("This is a quick answer from AI. No websites were checked.",R.string.ui_41);
IDS.put("This is a quick answer from AI. It can be wrong. No websites were checked.",R.string.ui_42);
IDS.put("Check online",R.string.ui_43);
IDS.put("Read source",R.string.ui_44);
IDS.put("Read more",R.string.ui_45);
IDS.put("Show less",R.string.ui_46);
IDS.put("Only this message’s text is sent when you tap Check.",R.string.ui_47);
IDS.put("Only this message is sent for checking.",R.string.ui_48);
IDS.put("Only these words are sent. The picture stays on your phone.",R.string.ui_49);
IDS.put("Nothing to check",R.string.ui_50);
IDS.put("This looks like a personal message.",R.string.ui_51);
IDS.put("Check anyway",R.string.ui_52);
IDS.put("Check this picture?",R.string.ui_53);
IDS.put("Tap to see what we found.",R.string.ui_54);
IDS.put("Could not read any words",R.string.ui_55);
IDS.put("Could not read this picture",R.string.ui_56);
IDS.put("Try a clearer picture.",R.string.ui_57);
IDS.put("Share a clearer picture to Verity.",R.string.ui_58);
IDS.put("Share this picture to Verity to read it.",R.string.ui_59);
IDS.put("Check a message",R.string.ui_60);
IDS.put("Check a picture",R.string.ui_61);
IDS.put("Last check",R.string.ui_62);
IDS.put("Social media",R.string.ui_63);
IDS.put("Settings",R.string.ui_64);
IDS.put("Paste or type a message",R.string.ui_65);
IDS.put("Add a message first.",R.string.ui_66);
IDS.put("WhatsApp helper",R.string.ui_67);
IDS.put("Message helper",R.string.ui_68);
IDS.put("People to check",R.string.ui_69);
IDS.put("Checking key",R.string.ui_70);
IDS.put("Costs",R.string.ui_71);
IDS.put("How it works",R.string.ui_72);
IDS.put("SMS scam warnings",R.string.ui_73);
IDS.put("On for unknown senders",R.string.ui_74);
IDS.put("SMS warnings are off",R.string.ui_75);
IDS.put("Warn me about possible scams while I read Google Messages.",R.string.ui_76);
IDS.put("Saved contacts are skipped. SMS stays on this phone.",R.string.ui_77);
IDS.put("Turn on",R.string.ui_78);
IDS.put("Turn off",R.string.ui_79);
IDS.put("Turn on the message helper",R.string.ui_80);
IDS.put("Skip people you know",R.string.ui_81);
IDS.put("Allow contacts access so we can skip saved contacts. Your contacts and SMS stay on this phone.",R.string.ui_82);
IDS.put("Language",R.string.ui_83);
IDS.put("Add a person",R.string.ui_84);
IDS.put("Stop checking this person?",R.string.ui_85);
IDS.put("Keep",R.string.ui_86);
IDS.put("Remove",R.string.ui_87);
IDS.put("App language",R.string.ui_88);
IDS.put("Messages in English and Hindi are checked in either language.",R.string.ui_89);
IDS.put("Could not open contacts.",R.string.ui_90);
IDS.put("Added. Open their WhatsApp chat.",R.string.ui_91);
IDS.put("Could not add this person. Please try again.",R.string.ui_92);
IDS.put("OpenRouter key",R.string.ui_93);
IDS.put("Please enter a valid key.",R.string.ui_94);
IDS.put("Saved on this phone. No computer needed.",R.string.ui_95);
IDS.put("A key is saved on this phone. Enter a new one to replace it.",R.string.ui_96);
IDS.put("One-time setup\nAdd your checking key in Settings.",R.string.ui_97);
IDS.put("Turn on the helper\nOpen Settings to get started.",R.string.ui_98);
IDS.put("Ready\nOpen WhatsApp.",R.string.ui_99);
IDS.put("Ready\nOpen Messages.",R.string.ui_100);
IDS.put("Ready\nOpen WhatsApp or Messages.",R.string.ui_101);
IDS.put("Keep checks on",R.string.ui_102);
IDS.put("One more step\nTap Keep checks on.",R.string.ui_103);
IDS.put("Allow Verity to stay on while you read WhatsApp.",R.string.ui_104);
IDS.put("Background checks are allowed.",R.string.ui_105);
IDS.put("Open Battery and choose Unrestricted.",R.string.ui_106);
IDS.put("Add the checking key in Settings first.",R.string.ui_107);
IDS.put("Add the checking key in Settings.",R.string.ui_108);
IDS.put("Connect to Wi-Fi or mobile data, then try again.",R.string.ui_109);
IDS.put("The connection stopped. Check Wi-Fi or mobile data, then try again.",R.string.ui_110);
IDS.put("This check was interrupted. Please try again.",R.string.ui_111);
IDS.put("The checking service is busy. Please try again.",R.string.ui_112);
IDS.put("The checking key has stopped working. Open Settings.",R.string.ui_113);
IDS.put("The checking credit is used up. Open Settings.",R.string.ui_114);
IDS.put("The demo budget is used up. Open Settings for costs.",R.string.ui_115);
IDS.put("Please wait for the current check.",R.string.ui_116);
IDS.put("No recent check on this phone.",R.string.ui_117);
IDS.put("This was checked on your phone.",R.string.ui_118);
IDS.put("Experimental AI image check: may be made with AI. This does not tell us whether the message is true.",R.string.ui_119);
IDS.put("Experimental AI image check: cannot tell if this image was made with AI.",R.string.ui_120);
IDS.put("The file names an AI tool. File details can be changed.",R.string.ui_121);
IDS.put("The answer was cut short. Please try again.",R.string.ui_122);
IDS.put("We could not find a clear answer. Try again later.",R.string.ui_123);
}
static String language(Context context){
    String selected=context.getSharedPreferences("ui_language",Context.MODE_PRIVATE).getString("language","en");
    return "hi".equals(selected)?"hi":"en";
}
static void setLanguage(Context context,String selected){
    context.getSharedPreferences("ui_language",Context.MODE_PRIVATE).edit().putString("language","hi".equals(selected)?"hi":"en").putBoolean("setup_complete",true).apply();
}
static String text(Context context,String value){
    if(value==null)return "";Integer id=IDS.get(value);if(id==null)return value;
    Configuration config=new Configuration(context.getResources().getConfiguration());config.setLocale(java.util.Locale.forLanguageTag(language(context)));
    return context.createConfigurationContext(config).getString(id);
}
static android.app.AlertDialog.Builder dialog(Context context){return new android.app.AlertDialog.Builder(context){
    public android.app.AlertDialog.Builder setTitle(CharSequence value){return super.setTitle(text(context,String.valueOf(value)));}
    public android.app.AlertDialog.Builder setMessage(CharSequence value){return super.setMessage(value==null?null:text(context,value.toString()));}
    public android.app.AlertDialog.Builder setItems(CharSequence[] items,DialogInterface.OnClickListener listener){CharSequence[] translated=new CharSequence[items.length];for(int i=0;i<items.length;i++)translated[i]=text(context,items[i].toString());return super.setItems(translated,listener);}
    public android.app.AlertDialog.Builder setPositiveButton(CharSequence value,DialogInterface.OnClickListener listener){return super.setPositiveButton(text(context,value.toString()),listener);}
    public android.app.AlertDialog.Builder setNegativeButton(CharSequence value,DialogInterface.OnClickListener listener){return super.setNegativeButton(text(context,value.toString()),listener);}
    public android.app.AlertDialog.Builder setNeutralButton(CharSequence value,DialogInterface.OnClickListener listener){return super.setNeutralButton(text(context,value.toString()),listener);}
};}
}

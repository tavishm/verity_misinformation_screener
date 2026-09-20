"""Generate resource-backed English/Hindi UI strings from a reviewed small catalog."""
from pathlib import Path
from xml.sax.saxutils import escape
ROOT=Path(__file__).resolve().parents[2]/'mobile/android/app/src/main'
PAIRS={
'Verity':'Verity',
'Could not open this link. Try pasting the message instead.':'लिंक खुल नहीं पाया। संदेश के शब्द पेस्ट करके कोशिश करें।',
'Is this message true?':'क्या यह संदेश सही है?',
'Is this true?':'क्या यह सही है?',
'This may be a scam':'यह धोखाधड़ी हो सकती है',
'Please ignore it.':'इस पर ध्यान न दें।',
'Please ignore this message.':'इस संदेश पर ध्यान न दें।',
'It may be asking for money, passwords or private details. This warning was made on your phone.':'इसमें पैसे, पासवर्ड या निजी जानकारी माँगी जा सकती है। यह चेतावनी आपके फ़ोन ने दी है।',
'Check':'जाँचें','Yes, check':'हाँ, जाँचें','Not now':'अभी नहीं','Ignore':'छोड़ दें','Why?':'क्यों?',
'Close':'बंद करें','Done':'हो गया','Back':'वापस','OK':'ठीक है','Cancel':'रद्द करें','Save':'सहेजें','Continue':'आगे बढ़ें',
'Checking…':'जाँच हो रही है…','Checking online…':'ऑनलाइन जाँच हो रही है…','Reading the link…':'लिंक पढ़ रहे हैं…','Reading the picture…':'तस्वीर पढ़ रहे हैं…','One moment…':'एक पल…',
'Looks false':'गलत लगता है','Looks true':'सही लगता है','Do not share yet':'अभी आगे न भेजें',
'Please do not forward this.':'इसे आगे न भेजें।','The claim appears correct.':'यह बात सही लगती है।',
'We could not find a clear answer.':'इसकी पुष्टि नहीं हो पाई।','We could not check this.':'इसकी जाँच नहीं हो पाई।',
'Could not check':'जाँच नहीं हो पाई','Please try again.':'फिर कोशिश करें।','Try again':'फिर कोशिश करें',
'Quick AI check · can be wrong':'AI की छोटी जाँच · गलती हो सकती है','Quick AI check':'AI की छोटी जाँच','Online check':'ऑनलाइन जाँच','Checked online':'ऑनलाइन जाँचा गया',
'About this check':'इस जाँच के बारे में','About this warning':'इस चेतावनी के बारे में',
'This is a quick answer from AI. No websites were checked.':'यह AI का जवाब है। वेबसाइट से जाँच नहीं हुई है।',
'This is a quick answer from AI. It can be wrong. No websites were checked.':'यह AI का जवाब है। गलती हो सकती है। वेबसाइट से जाँच नहीं हुई है।',
'Check online':'ऑनलाइन जाँचें','Read source':'स्रोत पढ़ें','Read more':'पूरा पढ़ें','Show less':'कम दिखाएँ',
'Only this message’s text is sent when you tap Check.':'जाँचें दबाने पर केवल इस संदेश के शब्द भेजे जाएँगे।',
'Only this message is sent for checking.':'जाँच के लिए केवल यह संदेश भेजा जाएगा।',
'Only these words are sent. The picture stays on your phone.':'केवल ये शब्द भेजे जाएँगे। तस्वीर फ़ोन पर ही रहेगी।',
'Nothing to check':'जाँच की ज़रूरत नहीं','This looks like a personal message.':'यह निजी संदेश लगता है।','Check anyway':'फिर भी जाँचें',
'Check this picture?':'इस तस्वीर की जाँच करें?',
'Tap to see what we found.':'जानने के लिए दबाएँ।','Could not read any words':'तस्वीर के शब्द पढ़ नहीं पाए',
'Could not read this picture':'तस्वीर पढ़ नहीं पाए','Try a clearer picture.':'साफ़ तस्वीर से कोशिश करें।',
'Share a clearer picture to Verity.':'साफ़ तस्वीर Verity में साझा करें।',
'Share this picture to Verity to read it.':'पढ़ने के लिए तस्वीर Verity में साझा करें।',
'Check a message':'संदेश जाँचें','Check a picture':'तस्वीर जाँचें','Last check':'पिछली जाँच','Social media':'सोशल मीडिया','Settings':'सेटिंग्स',
'Paste or type a message':'संदेश यहाँ लिखें या पेस्ट करें','Add a message first.':'पहले संदेश लिखें।',
'WhatsApp helper':'WhatsApp सहायक','Message helper':'संदेश सहायक','People to check':'किनके संदेश जाँचें','Checking key':'जाँच की API कुंजी','Costs':'खर्च','How it works':'यह कैसे काम करता है',
'SMS scam warnings':'SMS में धोखाधड़ी की चेतावनी',
'On for unknown senders':'अनजान लोगों के लिए चालू है',
'SMS warnings are off':'SMS की चेतावनी बंद है',
'Warn me about possible scams while I read Google Messages.':'Google Messages पढ़ते समय धोखाधड़ी जैसे संदेशों पर चेतावनी दें।',
'Saved contacts are skipped. SMS stays on this phone.':'सहेजे हुए संपर्कों के संदेश नहीं जाँचे जाते। SMS फ़ोन पर ही रहता है।',
'Turn on':'चालू करें','Turn off':'बंद करें',
'Turn on the message helper':'संदेश सहायक चालू करें',
'Skip people you know':'जान-पहचान वालों को छोड़ें',
'Allow contacts access so we can skip saved contacts. Your contacts and SMS stay on this phone.':'सहेजे हुए संपर्कों को छोड़ने के लिए संपर्क पढ़ने की अनुमति दें। संपर्क और SMS फ़ोन पर ही रहते हैं।',
'Language':'भाषा','Add a person':'व्यक्ति जोड़ें','Stop checking this person?':'इनके संदेश जाँचना बंद करें?','Keep':'रहने दें','Remove':'हटाएँ',
'App language':'ऐप की भाषा','Messages in English and Hindi are checked in either language.':'ऐप की भाषा कोई भी हो, अंग्रेज़ी और हिन्दी के संदेश जाँचे जाएँगे।',
'Could not open contacts.':'संपर्क खुल नहीं पाए।','Added. Open their WhatsApp chat.':'जोड़ दिया। अब इनकी WhatsApp चैट खोलें।','Could not add this person. Please try again.':'जोड़ नहीं पाए। फिर कोशिश करें।',
'OpenRouter key':'OpenRouter कुंजी','Please enter a valid key.':'सही कुंजी डालें।',
'Saved on this phone. No computer needed.':'इस फ़ोन पर सहेजी जाएगी। कंप्यूटर की ज़रूरत नहीं।',
'A key is saved on this phone. Enter a new one to replace it.':'फ़ोन पर कुंजी सहेजी हुई है। बदलने के लिए नई कुंजी डालें।',
'One-time setup\nAdd your checking key in Settings.':'पहली बार की सेटिंग\nसेटिंग्स में जाँच की कुंजी डालें।',
'Turn on the helper\nOpen Settings to get started.':'सहायक चालू करें\nशुरू करने के लिए सेटिंग्स खोलें।',
'Ready\nOpen WhatsApp.':'तैयार है\nWhatsApp खोलें।',
'Ready\nOpen Messages.':'तैयार है\nMessages खोलें।',
'Ready\nOpen WhatsApp or Messages.':'तैयार है\nWhatsApp या Messages खोलें।',
'Keep checks on':'जाँच चालू रखें',
'One more step\nTap Keep checks on.':'बस एक और कदम\nजाँच चालू रखें दबाएँ।',
'Allow Verity to stay on while you read WhatsApp.':'WhatsApp पढ़ते समय Verity को चालू रहने दें।',
'Background checks are allowed.':'ऐप को पीछे चालू रहने की अनुमति है।',
'Open Battery and choose Unrestricted.':'बैटरी की सेटिंग खोलें और कोई पाबंदी नहीं चुनें।',
'Add the checking key in Settings first.':'पहले सेटिंग्स में जाँच की कुंजी डालें।',
'Add the checking key in Settings.':'सेटिंग्स में जाँच की कुंजी डालें।',
'Connect to Wi-Fi or mobile data, then try again.':'Wi-Fi या मोबाइल डेटा चालू करके फिर कोशिश करें।',
'The connection stopped. Check Wi-Fi or mobile data, then try again.':'इंटरनेट बंद हो गया। Wi-Fi या मोबाइल डेटा जाँचकर फिर कोशिश करें।',
'This check was interrupted. Please try again.':'जाँच रुक गई थी। फिर कोशिश करें।',
'The checking service is busy. Please try again.':'जाँच सेवा व्यस्त है। फिर कोशिश करें।',
'The checking key has stopped working. Open Settings.':'जाँच की कुंजी काम नहीं कर रही। सेटिंग्स खोलें।',
'The checking credit is used up. Open Settings.':'जाँच के पैसे खत्म हो गए। सेटिंग्स खोलें।',
'The demo budget is used up. Open Settings for costs.':'खर्च की सीमा पूरी हो गई। सेटिंग्स में खर्च देखें।',
'Please wait for the current check.':'पहले चल रही जाँच पूरी होने दें।',
'No recent check on this phone.':'इस फ़ोन पर अभी कोई जाँच नहीं हुई।',
'This was checked on your phone.':'यह जाँच आपके फ़ोन पर हुई है।',
'Experimental AI image check: may be made with AI. This does not tell us whether the message is true.':'तस्वीर शायद AI से बनी है। यह जाँच अभी प्रयोग में है। इससे संदेश सही या गलत साबित नहीं होता।',
'Experimental AI image check: cannot tell if this image was made with AI.':'पता नहीं चल पाया कि तस्वीर AI से बनी है या नहीं।',
'The file names an AI tool. File details can be changed.':'तस्वीर की फ़ाइल में AI टूल का नाम है। यह जानकारी बदली जा सकती है।',
'The answer was cut short. Please try again.':'पूरा जवाब नहीं आया। फिर कोशिश करें।',
'We could not find a clear answer. Try again later.':'पुष्टि नहीं हो पाई। बाद में कोशिश करें।',
}
def main():
 java=['package org.fairc.forwardcheck;','import android.content.*;','import android.content.res.Configuration;','import java.util.*;','final class I18n {','private static final Map<String,Integer> IDS=new HashMap<>();','static {']
 for lang in ['en','hi']:
  folder=ROOT/'res'/('values' if lang=='en' else 'values-hi');folder.mkdir(exist_ok=True)
  lines=['<resources>']
  for i,(en,hi) in enumerate(PAIRS.items()):
   text=en if lang=='en' else hi
   text=escape(text).replace("'","\\'").replace('"','\\"').replace('\n','\\n')
   lines.append(f'    <string name="ui_{i}">{text}</string>')
  lines.append('</resources>');(folder/'ui_strings.xml').write_text('\n'.join(lines)+'\n')
 import json
 for i,en in enumerate(PAIRS):java.append('IDS.put('+json.dumps(en,ensure_ascii=False)+',R.string.ui_'+str(i)+');')
 java.extend(['}', '''static String language(Context context){
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
}'''])
 (ROOT/'java/org/fairc/forwardcheck/I18n.java').write_text('\n'.join(java)+'\n')
if __name__=='__main__':main()

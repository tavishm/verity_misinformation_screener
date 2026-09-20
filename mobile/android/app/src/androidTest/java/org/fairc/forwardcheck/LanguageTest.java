package org.fairc.forwardcheck;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** UI preference must never limit which message languages are screened. */
@RunWith(AndroidJUnit4.class)
public class LanguageTest {
    private Context isolatedHindiPhone() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration config=new Configuration(app.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag("hi"));
        return new ContextWrapper(app.createConfigurationContext(config)) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return super.getSharedPreferences("language_test_"+name,mode);
            }
        };
    }

    @Test public void defaultsToEnglishEvenOnHindiPhoneAndCanChange() {
        Context context=isolatedHindiPhone();
        context.getSharedPreferences("ui_language",0).edit().clear().commit();
        assertEquals("en",I18n.language(context));
        assertEquals("Check",I18n.text(context,"Check"));
        I18n.setLanguage(context,"hi");
        assertEquals("जाँचें",I18n.text(context,"Check"));
        assertEquals("इन शब्दों को न बदलें",I18n.text(context,"इन शब्दों को न बदलें"));
        I18n.setLanguage(context,"en");
        assertEquals("Check",I18n.text(context,"Check"));
    }

    @Test public void messagesInBothLanguagesWorkUnderEitherUiLanguage() {
        Context context=isolatedHindiPhone();
        String[] messages={"Happy Diwali","दिवाली की शुभकामनाएं","The Earth is flat.","पृथ्वी सपाट है।"};
        String[] original=new String[messages.length];
        I18n.setLanguage(context,"en");
        for(int i=0;i<messages.length;i++)original[i]=ScreeningClassifier.classify(context,messages[i]).action;
        I18n.setLanguage(context,"hi");
        for(int i=0;i<messages.length;i++)assertEquals(original[i],ScreeningClassifier.classify(context,messages[i]).action);
    }
}

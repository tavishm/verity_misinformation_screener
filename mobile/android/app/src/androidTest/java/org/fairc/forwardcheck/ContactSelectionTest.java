package org.fairc.forwardcheck;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ContactSelectionTest {
    @Test public void chosenPersonPersistsCanBeRemovedAndDoesNotMatchOtherCountries()throws Exception {
        Context phone=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context isolated=new ContextWrapper(phone){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("contact_test_"+name,mode);}
        };
        isolated.getSharedPreferences("chosen_contacts",0).edit().clear().commit();
        ChosenContacts people=new ChosenContacts(isolated);
        people.add("माँ","+91 98765 43210");
        assertTrue(new ChosenContacts(isolated).matches("माँ"));
        assertTrue(people.matches("+91 98765 43210"));assertTrue(people.matches("9876543210"));
        assertFalse(people.matches("+1 9876543210"));assertFalse(people.matches("माँ का समूह"));
        people.add("Mum","+919876543210");assertEquals(1,people.rows().length());
        assertTrue(people.matches("Mum"));assertFalse(people.matches("माँ"));
        people.remove(0);assertFalse(new ChosenContacts(isolated).matches("Mum"));
    }
}

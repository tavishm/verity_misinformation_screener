package org.fairc.forwardcheck;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.ContactsContract;
import java.util.*;
import java.util.concurrent.Executor;

/** Queries only the visible sender, on a worker. Contact data never leaves this phone. */
final class SmsSenderLookup implements AutoCloseable {
    private final Context context; private final Handler main; private final Executor worker; private final Runnable changed;
    private final Map<String,Result> cache=new LinkedHashMap<String,Result>(64,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Result> e){return size()>128;}};
    private final Set<String> pending=new HashSet<>(); private int epoch; private boolean closed,observing;
    private final ContentObserver observer;
    private static final class Result { final SmsPolicy.ContactStatus status;final long until;Result(SmsPolicy.ContactStatus s){status=s;until=SystemClock.elapsedRealtime()+(s==SmsPolicy.ContactStatus.UNRESOLVED?3000:60000);} }
    SmsSenderLookup(Context c,Handler h,Executor executor,Runnable callback) {
        context=c.getApplicationContext();main=h;worker=executor;changed=callback;
        observer=new ContentObserver(main){@Override public void onChange(boolean self){if(!closed){invalidate();changed.run();}}};
    }
    SmsPolicy.ContactStatus status(String sender) {
        if(closed || !SmsPrefs.contactsAllowed(context)) { invalidate();return SmsPolicy.ContactStatus.UNRESOLVED; }
        if(!observing)try{context.getContentResolver().registerContentObserver(ContactsContract.AUTHORITY_URI,true,observer);observing=true;}catch(RuntimeException denied){return SmsPolicy.ContactStatus.UNRESOLVED;}
        String key=ChosenContacts.normalize(sender);
        if(key.length()<2 || key.length()>100)return SmsPolicy.ContactStatus.UNRESOLVED;
        Result known=cache.get(key);if(known!=null && known.until>SystemClock.elapsedRealtime())return known.status;
        if(pending.add(key)) {
            final int generation=epoch;
            try{worker.execute(()->{
                SmsPolicy.ContactStatus result=lookup(sender);
                main.post(()->{if(closed || generation!=epoch)return;pending.remove(key);if(SmsPrefs.contactsAllowed(context))cache.put(key,new Result(result));changed.run();});
            });}catch(java.util.concurrent.RejectedExecutionException stopped){pending.remove(key);}
        }
        return SmsPolicy.ContactStatus.UNRESOLVED;
    }
    private SmsPolicy.ContactStatus lookup(String sender) {
        try {
            // PhoneLookup handles local/international number equivalence using
            // Android's contact provider. Do not guess using suffix matching.
            if(sender.matches("[+()\\-\\s\\p{Nd}]{3,35}")) {
                Uri uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(sender));
                try(Cursor c=context.getContentResolver().query(uri,new String[]{ContactsContract.PhoneLookup._ID},null,null,null)) {
                    if(c==null)return SmsPolicy.ContactStatus.UNRESOLVED;
                    if(c.moveToFirst())return SmsPolicy.ContactStatus.SAVED;
                }
            }
            // Handles a named contact shown in the header and saved sender IDs.
            try(Cursor c=context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID},ContactsContract.Contacts.DISPLAY_NAME_PRIMARY+" = ? COLLATE NOCASE",new String[]{sender.trim()},null)) {
                if(c==null)return SmsPolicy.ContactStatus.UNRESOLVED;
                return c.moveToFirst()?SmsPolicy.ContactStatus.SAVED:SmsPolicy.ContactStatus.UNKNOWN;
            }
        } catch(RuntimeException unavailable) { return SmsPolicy.ContactStatus.UNRESOLVED; }
    }
    private void invalidate(){epoch++;cache.clear();pending.clear();}
    @Override public void close(){closed=true;invalidate();if(observing)try{context.getContentResolver().unregisterContentObserver(observer);}catch(RuntimeException ignored){}observing=false;}
}

package org.fairc.forwardcheck.social;

import java.util.*;
import java.util.concurrent.*;

/** Exact-claim search evidence is shared across layout/media changes, never truth labels. */
final class SocialSearchCache<T> {
    private static final class Entry<T>{final CompletableFuture<T> future=new CompletableFuture<>();long expires;Entry(long expires){this.expires=expires;}}
    private final LinkedHashMap<String,Entry<T>> entries=new LinkedHashMap<>(128,.75f,true);
    T get(String key,long now,long ttl,Callable<T> load)throws Exception{
        Entry<T> entry;boolean owner=false;
        synchronized(this){entry=entries.get(key);if(entry==null||entry.future.isDone()&&entry.expires<=now){entry=new Entry<>(now+ttl);entries.put(key,entry);owner=true;}trim();}
        if(owner){try{entry.future.complete(load.call());}catch(Exception error){entry.expires=now+15_000;entry.future.completeExceptionally(error);}}
        try{return entry.future.get();}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException(cause);}
    }
    private void trim(){if(entries.size()<=128)return;Iterator<Map.Entry<String,Entry<T>>> it=entries.entrySet().iterator();while(entries.size()>128&&it.hasNext())if(it.next().getValue().future.isDone())it.remove();}
    synchronized void clear(){entries.clear();}
}

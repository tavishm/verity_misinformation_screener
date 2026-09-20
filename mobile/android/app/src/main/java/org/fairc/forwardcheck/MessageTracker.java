package org.fairc.forwardcheck;

import org.json.*;
import java.util.*;

/** Local sequence reconciliation. Stores hashes and choices, never message bodies.
 * Android view IDs are only matching hints. A new same-text copy has its own choice.
 */
final class MessageTracker {
    static final int MAX_CHATS = 80, MAX_MESSAGES = 300;
    static final class Item {
        final String signature, hint, instance; final boolean eligible;
        Item(String signature, String hint, boolean eligible) { this(signature,hint,eligible,""); }
        Item(String signature, String hint, boolean eligible,String instance) { this.signature=signature; this.hint=hint; this.eligible=eligible; this.instance=instance; }
    }
    static final class Entry {
        String id, signature, hint, instance; boolean settled;
        Entry(String id, Item item) { this.id=id; signature=item.signature; hint=item.hint; instance=item.instance; }
    }
    private final LinkedHashMap<String,List<Entry>> chats = new LinkedHashMap<>(16, .75f, true);
    private long serial;
    synchronized List<Entry> reconcile(String chat, List<Item> visible) {
        return reconcile(chat,visible,false);
    }
    synchronized List<Entry> reconcile(String chat, List<Item> visible,boolean screenEveryUnseen) {
        List<Entry> history = chats.get(chat); boolean first = history == null;
        if (first) { history = new ArrayList<>(); chats.put(chat, history); }
        int m=history.size(), n=visible.size(); int[][] score=new int[m+1][n+1];
        for(int i=m-1;i>=0;i--) for(int j=n-1;j>=0;j--) {
            int match = matches(history.get(i),visible.get(j)) ? 1000 + (!visible.get(j).hint.isEmpty() && history.get(i).hint.equals(visible.get(j).hint) ? 1 : 0) + score[i+1][j+1] : -1;
            score[i][j]=Math.max(match, Math.max(score[i+1][j],score[i][j+1]));
        }
        Map<Integer,Integer> matches=new HashMap<>(); int i=0,j=0;
        while(i<m && j<n) {
            int match=matches(history.get(i),visible.get(j)) ? 1000 + (!visible.get(j).hint.isEmpty() && history.get(i).hint.equals(visible.get(j).hint) ? 1 : 0) + score[i+1][j+1] : -1;
            if(match==score[i][j]) { matches.put(j,i); i++;j++; }
            else if(score[i+1][j]>=score[i][j+1]) i++; else j++;
        }
        // Partial viewports can be seen in a different order from the underlying
        // conversation. Reuse an explicit message identity even when it is not
        // part of the best sequence alignment; otherwise scrolling back to two
        // pictures previously seen separately creates a fresh, noisy copy.
        Map<String,Entry> identities=new HashMap<>();
        for(Entry entry:history)if(!entry.instance.isEmpty())identities.putIfAbsent(identity(entry.signature,entry.instance),entry);
        Set<String> used=new HashSet<>();
        List<Entry> out=new ArrayList<>(), merged=new ArrayList<>(); int consumed=0;
        int lastMatch=-1;for(Integer at:matches.keySet())lastMatch=Math.max(lastMatch,at);
        // Readable UI windows are not a chronological database. A large picture
        // can replace every previous bubble on screen. An unseen bottom message
        // must still be screened; lacking overlap does not mean it was handled.
        // WhatsApp offers only the bottom unseen message, preventing an old-
        // message cascade. SMS scans all unseen entries so a new greeting cannot
        // hide an earlier scam. Both retain previously acknowledged choices.
        for(j=0;j<n;j++) {
            Item item=visible.get(j); Entry entry;
            Integer old=matches.get(j);
            if(old!=null) {
                while(consumed<old) merged.add(history.get(consumed++));
                entry=history.get(old); consumed=old+1; entry.hint=item.hint;
                if(!item.instance.isEmpty())entry.instance=item.instance;
            } else {
                entry=item.instance.isEmpty()?null:identities.get(identity(item.signature,item.instance));
                if(entry!=null && used.contains(entry.id))entry=null;
                if(entry!=null)entry.hint=item.hint;
                else {
                entry=new Entry(Long.toString(++serial),item);
                // Eligibility can arrive after the image/text (for example a
                // forwarded label or unknown-sender header loading later).
                // Skipping an ineligible bubble is not a user's dismissal.
                // New arrivals after an existing visible message remain queued,
                // even if a greeting or another picture arrives immediately after.
                boolean appended=!first && lastMatch>=0 && j>lastMatch;
                entry.settled=!screenEveryUnseen && !appended && j<n-1;
                }
            }
            used.add(entry.id);merged.add(entry); out.add(entry);
        }
        while(consumed<m) merged.add(history.get(consumed++));
        Set<String> stored=new HashSet<>();merged.removeIf(entry->!stored.add(entry.id));
        if(merged.size()>MAX_MESSAGES) merged=new ArrayList<>(merged.subList(merged.size()-MAX_MESSAGES,merged.size()));
        chats.put(chat,merged); while(chats.size()>MAX_CHATS) chats.remove(chats.keySet().iterator().next());
        return out;
    }
    private static String identity(String signature,String instance){return signature+"|"+instance;}
    private static boolean matches(Entry entry,Item item) {
        return entry.signature.equals(item.signature) && (entry.instance.isEmpty() || item.instance.isEmpty() || entry.instance.equals(item.instance));
    }
    synchronized void settle(Collection<Entry> entries) { for(Entry e:entries) e.settled=true; }
    synchronized void settle(String id) { for(List<Entry> history:chats.values()) for(Entry e:history) if(e.id.equals(id)) e.settled=true; }
    synchronized String encode() {
        try { JSONObject root=new JSONObject().put("serial",serial), object=new JSONObject();
            for(Map.Entry<String,List<Entry>> chat:chats.entrySet()) { JSONArray rows=new JSONArray(); for(Entry e:chat.getValue()) rows.put(new JSONArray().put(e.id).put(e.signature).put(e.hint).put(e.settled).put(e.instance)); object.put(chat.getKey(),rows); }
            return root.put("chats",object).toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    synchronized void restore(String json) {
        chats.clear(); try { JSONObject root=new JSONObject(json); serial=root.optLong("serial"); JSONObject object=root.optJSONObject("chats"); if(object==null)return;
            Iterator<String> keys=object.keys(); while(keys.hasNext() && chats.size()<MAX_CHATS) { String key=keys.next(); JSONArray rows=object.getJSONArray(key); List<Entry> history=new ArrayList<>();
                for(int i=Math.max(0,rows.length()-MAX_MESSAGES);i<rows.length();i++) { JSONArray row=rows.getJSONArray(i); Entry e=new Entry(row.getString(0),new Item(row.getString(1),row.getString(2),true,row.optString(4,""))); e.settled=row.getBoolean(3); history.add(e); } chats.put(key,history); }
        } catch(Exception e) { chats.clear(); }
    }
}

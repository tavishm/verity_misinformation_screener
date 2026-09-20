package org.fairc.forwardcheck.social;

import java.util.*;

/** Independent reading pauses and result identity for every visible card. */
public final class SocialFeed {
    public static final class Reading {
        private final String identity;
        private final SocialDwell dwell=new SocialDwell();
        private Reading(String identity){this.identity=identity;}
        public SocialPost post(){return dwell.current();}
        public SocialDwell.Ticket begin(long now){return dwell.begin(now);}
    }
    private LinkedHashMap<String,Reading> visible=new LinkedHashMap<>();
    public void observe(List<SocialPost> posts,long now){
        LinkedHashMap<String,Reading> next=new LinkedHashMap<>();
        // Duplicate exact headlines share research, but keep separate badges.
        // Their physical order is independent of queue priority/reading score.
        List<SocialPost> ordered=new ArrayList<>(posts);ordered.sort(Comparator.comparingInt((SocialPost p)->p.top).thenComparingInt(p->p.left));
        Map<String,Integer> occurrences=new HashMap<>();Map<SocialPost,String> identities=new IdentityHashMap<>();
        for(SocialPost post:ordered){int n=occurrences.getOrDefault(post.key,0);occurrences.put(post.key,n+1);identities.put(post,post.key+":"+n);}
        for(SocialPost post:posts){
            String identity=identities.get(post);
            Reading reading=visible.get(identity);if(reading==null)reading=new Reading(identity);
            reading.dwell.observe(post,now);next.put(identity,reading);
        }
        visible=next;
    }
    public List<Reading> readings(){return new ArrayList<>(visible.values());}
    public boolean accepts(Reading reading,SocialDwell.Ticket ticket){return reading!=null&&visible.get(reading.identity)==reading&&reading.dwell.accepts(ticket);}
    public void clear(){visible.clear();}
}

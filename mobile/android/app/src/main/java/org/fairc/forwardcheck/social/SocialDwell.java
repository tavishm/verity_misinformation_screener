package org.fairc.forwardcheck.social;

/** Monotonic, testable dwell and stale-response guard. Never ties results to recycled views. */
public final class SocialDwell {
    public static final long PAUSE_MS=250;
    public static final class Ticket { public final long generation; public final String key; Ticket(long g,String k){generation=g;key=k;} }
    private SocialPost current; private long since,generation; private boolean submitted;
    public boolean observe(SocialPost post,long now) {
        boolean changed=current==null||post==null||!current.key.equals(post.key);
        boolean moved=!changed&&(Math.abs(post.top-current.top)>12||Math.abs(post.bottom-current.bottom)>12);
        // Once work has been submitted, coordinate-only movement is the same
        // card scrolling. Keep its ticket valid so the badge can follow it.
        // Before submission, movement still restarts the reading pause.
        if(changed||(moved&&!submitted)){generation++;since=now;submitted=false;}
        current=post;return changed||moved;
    }
    public void reset(long now){current=null;since=now;submitted=false;generation++;}
    public Ticket begin(long now){if(current==null||submitted||now-since<PAUSE_MS)return null;submitted=true;return new Ticket(generation,current.key);}
    public boolean accepts(Ticket ticket){return ticket!=null&&current!=null&&ticket.generation==generation&&ticket.key.equals(current.key);}
    public SocialPost current(){return current;}
}

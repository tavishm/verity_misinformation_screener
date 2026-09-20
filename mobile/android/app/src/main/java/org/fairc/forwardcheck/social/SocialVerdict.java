package org.fairc.forwardcheck.social;

import java.util.*;

public final class SocialVerdict {
    public static final class Source {
        public final String title,url,quote,published;
        public Source(String title,String url,String quote,String published){this.title=title;this.url=url;this.quote=quote;this.published=published;}
    }
    public final String kind,basis,note; public final long checkedAt; public final List<Source> sources;
    public SocialVerdict(String kind,String basis,String note,List<Source> sources){this.kind=kind;this.basis=basis;this.note=note;this.checkedAt=System.currentTimeMillis();this.sources=Collections.unmodifiableList(new ArrayList<>(sources));}
    public static SocialVerdict unclear(String note){return new SocialVerdict("uncertain","none",note,Collections.emptyList());}
    public boolean decisive(){return "true".equals(kind)||"false".equals(kind);}
    public String label(){return "true".equals(kind)?"Looks true":"false".equals(kind)?"Looks false":"skip".equals(kind)?"No factual claim":"Unsure";}
    public String sourceLabel(){
        if(sources.isEmpty())return "Source";String title=sources.get(0).title;int split=title.indexOf(':');
        String publisher=(split>0?title.substring(0,split):"Source").trim();return publisher.isEmpty()||publisher.length()>32?"Source":publisher;
    }
}

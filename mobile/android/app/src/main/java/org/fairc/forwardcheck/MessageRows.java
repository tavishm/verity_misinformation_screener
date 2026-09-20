package org.fairc.forwardcheck;

/** Positions from the message list's own scroll event, never screen coordinates. */
final class MessageRows {
    private String source="";
    private int first=-1,last=-1,total;
    void update(String source,int first,int last,int total){
        if(source==null||source.isEmpty()||first<0||last<first||total<=last){clear();return;}
        this.source=source;this.first=first;this.last=last;this.total=total;
    }
    void clear(){source="";first=-1;last=-1;total=0;}
    int row(String source,int children,int child){
        if(!this.source.equals(source)||first<0||children!=last-first+1||child<0||child>=children)return -1;
        return first+child;
    }
    boolean available(){return first>=0&&total>last;}
}

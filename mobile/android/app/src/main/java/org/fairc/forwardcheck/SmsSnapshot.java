package org.fairc.forwardcheck;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** SMS uses the existing dismissal/overlay machinery, with no image or online route. */
final class SmsSnapshot {
    static ChatSnapshot read(Context context,AccessibilityNodeInfo root,SmsSenderLookup contacts) {
        ChatSnapshot frame=new ChatSnapshot();frame.sms=true;frame.windowId=root.getWindowId();frame.windowBounds=new Rect();root.getBoundsInScreen(frame.windowBounds);
        if(!SmsPrefs.enabled(context)||!SmsPrefs.contactsAllowed(context))return frame;
        int[] count={0,0};SmsExtractor.Node tree=copy(root,0,count);frame.nodes=count[0];
        if(tree==null||count[1]!=0)return frame;
        String app=String.valueOf(root.getPackageName());SmsExtractor.Frame extracted=SmsExtractor.extract(app,tree);
        frame.title=extracted.sender;frame.group=extracted.group;
        if(frame.title.isEmpty()||frame.group||extracted.messages.isEmpty())return frame;
        SmsPolicy.ContactStatus status=contacts.status(frame.title);frame.unknown=status==SmsPolicy.ContactStatus.UNKNOWN;
        frame.chat=ChatSnapshot.hash(app+"|"+ChosenContacts.normalize(frame.title)+"|false");
        for(SmsExtractor.Message message:extracted.messages) {
            ChatSnapshot.Bubble b=new ChatSnapshot.Bubble();b.text=message.text;b.time=message.time;b.hint=message.hint;b.outgoing=message.outgoing;b.source="sms";
            b.eligible=SmsPolicy.eligible(true,message.incoming,frame.group,status);
            b.bounds=new Rect(message.left,message.top,message.right,message.bottom);
            // Messages may reveal/hide timestamps when a bubble is tapped.
            // That UI change must not turn a dismissed message into a new one.
            b.signature=ChatSnapshot.hash(b.text+"|"+b.outgoing+"|sms");frame.bubbles.add(b);
        }
        return frame;
    }
    private static SmsExtractor.Node copy(AccessibilityNodeInfo node,int depth,int[] budget) {
        if(!node.isVisibleToUser())return null;
        if(depth>SmsExtractor.MAX_DEPTH||++budget[0]>SmsExtractor.MAX_NODES){budget[1]=1;return null;}
        SmsExtractor.Node n=new SmsExtractor.Node();n.id=ChatSnapshot.value(node.getViewIdResourceName());n.text=ChatSnapshot.value(node.getText());n.description=ChatSnapshot.value(node.getContentDescription());n.hint=Integer.toString(node.hashCode());n.editable=node.isEditable();n.showingHint=node.isShowingHintText();Rect r=new Rect();node.getBoundsInScreen(r);n.left=r.left;n.top=r.top;n.right=r.right;n.bottom=r.bottom;
        for(int i=0;i<node.getChildCount();i++){
            if(budget[0]>=SmsExtractor.MAX_NODES){budget[1]=1;break;}
            AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{SmsExtractor.Node copied=copy(child,depth+1,budget);if(copied!=null)n.children.add(copied);}finally{child.recycle();}
        }
        return n;
    }
}

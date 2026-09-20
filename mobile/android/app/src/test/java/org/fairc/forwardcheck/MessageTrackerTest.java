package org.fairc.forwardcheck;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class MessageTrackerTest {
    private List<MessageTracker.Item> items(String... signatures){List<MessageTracker.Item> rows=new ArrayList<>();for(int i=0;i<signatures.length;i++)rows.add(new MessageTracker.Item(signatures[i],"node-"+i,true));return rows;}
    @Test public void firstWindowOnlyNewestCanPrompt(){MessageTracker t=new MessageTracker();List<MessageTracker.Entry> e=t.reconcile("a",items("old","new"));assertTrue(e.get(0).settled);assertFalse(e.get(1).settled);}
    @Test public void dismissalSurvivesRedraw(){MessageTracker t=new MessageTracker();List<MessageTracker.Entry> a=t.reconcile("a",items("hello"));t.settle(a);List<MessageTracker.Entry>b=t.reconcile("a",Collections.singletonList(new MessageTracker.Item("hello","new-node",true)));assertEquals(a.get(0).id,b.get(0).id);assertTrue(b.get(0).settled);}
    @Test public void sameTextNewCopyIsNotSilenced(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("same")));List<MessageTracker.Entry> b=t.reconcile("a",items("same","same"));assertTrue(b.get(0).settled);assertFalse(b.get(1).settled);assertNotEquals(b.get(0).id,b.get(1).id);}
    @Test public void appendAfterPersonalStillWorks(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("claim")));t.reconcile("a",Arrays.asList(new MessageTracker.Item("claim","0",true),new MessageTracker.Item("greeting","1",false)));List<MessageTracker.Entry>b=t.reconcile("a",items("claim","greeting","newclaim"));assertFalse(b.get(2).settled);}
    @Test public void restartKeepsChoices(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("a","b")));MessageTracker restored=new MessageTracker();restored.restore(t.encode());List<MessageTracker.Entry> e=restored.reconcile("a",items("a","b","c"));assertTrue(e.get(1).settled);assertFalse(e.get(2).settled);}
    @Test public void differentChatsIndependent(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("same")));assertFalse(t.reconcile("b",items("same")).get(0).settled);assertTrue(t.reconcile("a",items("same")).get(0).settled);}
    @Test public void scrollbackDoesNotReAlert(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("b","c")));List<MessageTracker.Entry> e=t.reconcile("a",items("a","b"));assertTrue(e.get(0).settled);assertTrue(e.get(1).settled);assertTrue(t.reconcile("a",items("b","c")).get(1).settled);}
    @Test public void previouslyUnseenBottomMessageCanBeScreened(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("a","c")));assertFalse(t.reconcile("a",items("a","b")).get(1).settled);}
    @Test public void largeNewImageWithoutOverlapIsNotSilenced(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("old text","old greeting")));List<MessageTracker.Entry> image=t.reconcile("a",items("new image"));assertFalse(image.get(0).settled);t.settle(image);assertTrue(t.reconcile("a",items("new image")).get(0).settled);}
    @Test public void returningFromDifferentWindowDoesNotBreakNewArrivals(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("a","b")));t.settle(t.reconcile("a",items("scrollback x","scrollback y")));List<MessageTracker.Entry> current=t.reconcile("a",items("a","b","new image"));assertTrue(current.get(1).settled);assertFalse(current.get(2).settled);}
    @Test public void nonOverlappingImageChoicePersistsAcrossRestart(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("old")));t.settle(t.reconcile("a",items("photo")));MessageTracker next=new MessageTracker();next.restore(t.encode());assertTrue(next.reconcile("a",items("photo")).get(0).settled);}
    @Test public void eligibilityArrivingAfterImageDoesNotDismissIt(){MessageTracker t=new MessageTracker();List<MessageTracker.Entry> before=t.reconcile("a",Collections.singletonList(new MessageTracker.Item("photo","node",false)));List<MessageTracker.Entry> after=t.reconcile("a",Collections.singletonList(new MessageTracker.Item("photo","node",true)));assertEquals(before.get(0).id,after.get(0).id);assertFalse(after.get(0).settled);t.settle(after);assertTrue(t.reconcile("a",Collections.singletonList(new MessageTracker.Item("photo","new-node",true))).get(0).settled);}
    @Test public void ackOneDoesNotSilenceOtherCopy(){MessageTracker t=new MessageTracker();List<MessageTracker.Entry> e=t.reconcile("a",items("same","same"));t.settle(e.get(0).id);assertFalse(e.get(1).settled);}
    @Test public void repeatedScansDontAllocateNewIds(){MessageTracker t=new MessageTracker();String id=t.reconcile("a",items("same")).get(0).id;for(int i=0;i<1000;i++)assertEquals(id,t.reconcile("a",items("same")).get(0).id);}
    @Test public void contentEditIsNewClaim(){MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("anchor","oldclaim")));List<MessageTracker.Entry> e=t.reconcile("a",items("anchor","newclaim"));/* Cannot identify an edit safely from a partial UI; preserve quiet instead of claiming it's unchanged. */assertNotEquals(t.reconcile("a",items("anchor","oldclaim")).get(1).id,e.get(1).id);}
    @Test public void storageBounded(){MessageTracker t=new MessageTracker();for(int i=0;i<1000;i++)t.reconcile("chat"+i,items("a","b"));assertTrue(t.encode().length()<25000);}
    @Test public void corruptStateRecovers(){MessageTracker t=new MessageTracker();t.restore("oops");assertFalse(t.reconcile("a",items("new")).get(0).settled);}
    private MessageTracker.Item photo(String row){return new MessageTracker.Item("uncaptioned-photo-at-11:30","recycled-view",true,row);}
    @Test public void sameMinutePhotoReplacingPreviousViewportIsNew(){
        MessageTracker t=new MessageTracker();List<MessageTracker.Entry> first=t.reconcile("a",Collections.singletonList(photo("row:41")));t.settle(first);
        List<MessageTracker.Entry> second=t.reconcile("a",Collections.singletonList(photo("row:42")));
        assertFalse(second.get(0).settled);assertNotEquals(first.get(0).id,second.get(0).id);
        t.settle(second);assertTrue(t.reconcile("a",Collections.singletonList(photo("row:41"))).get(0).settled);
        assertTrue(t.reconcile("a",Collections.singletonList(photo("row:42"))).get(0).settled);
    }
    @Test public void pictureChoiceSurvivesRedrawWithSameConversationRow(){
        MessageTracker t=new MessageTracker();List<MessageTracker.Entry> first=t.reconcile("a",Collections.singletonList(photo("row:41")));t.settle(first);
        MessageTracker.Item redraw=new MessageTracker.Item("uncaptioned-photo-at-11:30","different-view",true,"row:41");
        assertEquals(first.get(0).id,t.reconcile("a",Collections.singletonList(redraw)).get(0).id);
        assertTrue(t.reconcile("a",Collections.singletonList(redraw)).get(0).settled);
    }
    @Test public void stablePictureIdentitySurvivesRestart(){
        MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",Collections.singletonList(photo("uid:old"))));
        MessageTracker next=new MessageTracker();next.restore(t.encode());
        assertTrue(next.reconcile("a",Collections.singletonList(photo("uid:old"))).get(0).settled);
        assertFalse(next.reconcile("a",Collections.singletonList(photo("uid:new"))).get(0).settled);
    }
    @Test public void legacyDismissalMigratesWithoutReset(){
        MessageTracker t=new MessageTracker();t.restore("{\"serial\":1,\"chats\":{\"a\":[[\"1\",\"uncaptioned-photo-at-11:30\",\"old-node\",true]]}}");
        assertTrue(t.reconcile("a",Collections.singletonList(photo("row:41"))).get(0).settled);
        assertFalse(t.reconcile("a",Collections.singletonList(photo("row:42"))).get(0).settled);
    }
    @Test public void greetingAfterNewPictureDoesNotSilencePicture(){
        MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("anchor")));
        List<MessageTracker.Entry> entries=t.reconcile("a",Arrays.asList(items("anchor").get(0),photo("row:42"),new MessageTracker.Item("Happy Diwali","greeting",true)));
        assertFalse(entries.get(1).settled);t.settle(entries.get(2).id);assertFalse(entries.get(1).settled);
    }
    @Test public void acknowledgingOneNewPictureDoesNotAcknowledgeItsNeighbour(){
        MessageTracker t=new MessageTracker();t.settle(t.reconcile("a",items("anchor")));
        List<MessageTracker.Entry> entries=t.reconcile("a",Arrays.asList(items("anchor").get(0),photo("row:42"),photo("row:43")));
        t.settle(entries.get(2).id);assertFalse(entries.get(1).settled);assertTrue(entries.get(2).settled);
    }
    @Test public void separatePictureViewportsCanLaterOverlapWithoutRevivingChoices(){
        MessageTracker t=new MessageTracker();List<MessageTracker.Entry> first=t.reconcile("a",Collections.singletonList(photo("row:41")));t.settle(first);
        List<MessageTracker.Entry> second=t.reconcile("a",Collections.singletonList(photo("row:42")));t.settle(second);
        List<MessageTracker.Entry> both=t.reconcile("a",Arrays.asList(photo("row:41"),photo("row:42")));
        assertEquals(first.get(0).id,both.get(0).id);assertEquals(second.get(0).id,both.get(1).id);
        assertTrue(both.get(0).settled);assertTrue(both.get(1).settled);
    }
}

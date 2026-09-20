package org.fairc.forwardcheck;
import org.junit.Test;
import static org.junit.Assert.*;
public class MessageRowsTest {
    @Test public void samePictureKeepsConversationPositionAfterScrolling(){
        MessageRows rows=new MessageRows();rows.update("list",41,42,43);assertEquals(42,rows.row("list",2,1));
        rows.update("list",42,42,43);assertEquals(42,rows.row("list",1,0));
    }
    @Test public void newlyAppendedPictureHasAnotherPositionInSameScreenSlot(){
        MessageRows rows=new MessageRows();rows.update("list",42,42,43);assertEquals(42,rows.row("list",1,0));
        rows.update("list",43,43,44);assertEquals(43,rows.row("list",1,0));
    }
    @Test public void unrelatedOrChangedListCannotSupplyPictureIdentity(){
        MessageRows rows=new MessageRows();rows.update("list",41,42,43);
        assertEquals(-1,rows.row("other-list",2,0));assertEquals(-1,rows.row("list",1,0));
        rows.clear();assertEquals(-1,rows.row("list",2,0));assertFalse(rows.available());
    }
    @Test public void IncompleteAccessibilityPositionsAreRejected(){
        MessageRows rows=new MessageRows();rows.update("list",-1,-1,0);assertFalse(rows.available());
        rows.update("list",4,5,5);assertFalse(rows.available());
    }
}

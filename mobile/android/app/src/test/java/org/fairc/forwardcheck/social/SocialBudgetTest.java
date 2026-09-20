package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
public class SocialBudgetTest {
    @Test public void pendingAndUnknownChargesCount(){SocialBudget b=new SocialBudget();double spent=SocialBudget.LIMIT_USD-.01;b.reserve("a",spent);assertFalse(b.canStart(.04));assertTrue(b.canStart(.003));b.settle("a",Double.NaN);assertEquals(spent,b.total(),1e-9);}
    @Test public void settlementReleasesUnusedReserveOnce(){SocialBudget b=new SocialBudget();b.reserve("a",.04);b.reserve("b",.003);b.settle("a",.001);b.settle("a",.001);assertEquals(.004,b.total(),1e-9);}
    @Test public void cannotCrossMonthlyGuard(){SocialBudget b=new SocialBudget();b.reserve("old",SocialBudget.LIMIT_USD-.04);assertTrue(b.canStart(.04));b.reserve("new",.04);assertFalse(b.canStart(.003));assertEquals("$20",SocialBudget.limitLabel());}
}

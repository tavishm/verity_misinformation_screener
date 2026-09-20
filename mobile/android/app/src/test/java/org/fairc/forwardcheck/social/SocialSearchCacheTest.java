package org.fairc.forwardcheck.social;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class SocialSearchCacheTest {
    @Test public void simultaneousSameClaimStartsOnlyOneSearch()throws Exception{
        SocialSearchCache<String> cache=new SocialSearchCache<>();AtomicInteger calls=new AtomicInteger();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Callable<String> fetch=()->{calls.incrementAndGet();started.countDown();release.await();return "actual sources";};Future<String> a=pool.submit(()->cache.get("same exact claim",100,60000,fetch));assertTrue(started.await(2,TimeUnit.SECONDS));Future<String> b=pool.submit(()->cache.get("same exact claim",200,60000,fetch));release.countDown();assertEquals("actual sources",a.get(2,TimeUnit.SECONDS));assertEquals("actual sources",b.get(2,TimeUnit.SECONDS));assertEquals(1,calls.get());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test public void changedClaimIsNotInheritedAndExpirationRefreshes()throws Exception{
        SocialSearchCache<Integer> cache=new SocialSearchCache<>();AtomicInteger calls=new AtomicInteger();assertEquals(Integer.valueOf(1),cache.get("20 arrests",100,1000,calls::incrementAndGet));assertEquals(Integer.valueOf(1),cache.get("20 arrests",900,1000,calls::incrementAndGet));assertEquals(Integer.valueOf(2),cache.get("200 arrests",900,1000,calls::incrementAndGet));assertEquals(Integer.valueOf(3),cache.get("20 arrests",1200,1000,calls::incrementAndGet));
    }
    @Test public void immediateFailureCannotBuyAnotherSearchOnScroll()throws Exception{
        SocialSearchCache<Integer> cache=new SocialSearchCache<>();AtomicInteger calls=new AtomicInteger();for(int i=0;i<2;i++)try{cache.get("same",100+i,60000,()->{calls.incrementAndGet();throw new java.io.IOException("network");});fail();}catch(java.io.IOException expected){}assertEquals(1,calls.get());
    }
}

package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        CountDownLatch count = new CountDownLatch(500);

        Runnable task = ()-> {
           for (int i = 0; i < 100; i++) {
               System.out.println(redisIdWorker.nextId("Test:"));
           }
           count.countDown();
       };
        long begin  = System.currentTimeMillis();
        for (int i = 0; i < 500; i++){
           es.submit(task);
       }
        count.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));
    }

}

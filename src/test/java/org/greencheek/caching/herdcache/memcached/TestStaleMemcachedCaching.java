package org.greencheek.caching.herdcache.memcached;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import net.spy.memcached.ConnectionFactoryBuilder;
import org.greencheek.caching.herdcache.CacheWithExpiry;
import org.greencheek.caching.herdcache.RequiresShutdown;
import org.greencheek.caching.herdcache.memcached.config.builder.ElastiCacheCacheConfigBuilder;
import org.greencheek.caching.herdcache.memcached.keyhashing.KeyHashingType;
import org.greencheek.caching.herdcache.memcached.metrics.YammerMetricsRecorder;
import org.greencheek.caching.herdcache.memcached.util.MemcachedDaemonFactory;
import org.greencheek.caching.herdcache.memcached.util.MemcachedDaemonWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by dominictootell on 25/08/2014.
 */
public class TestStaleMemcachedCaching {
    private MemcachedDaemonWrapper memcached;
    private ListeningExecutorService executorService;
    CacheWithExpiry<String> cache;
    MetricRegistry registry;
    private ConsoleReporter reporter;


    @Before
    public void setUp() {
        registry = new MetricRegistry();
        reporter = ConsoleReporter.forRegistry(registry)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .build();

        executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(10));

        memcached = MemcachedDaemonFactory.createMemcachedDaemon(false);

        if(memcached.getDaemon()==null) {
            throw new RuntimeException("Unable to start local memcached");
        }

        cache = createCache(memcached.getPort());


    }

    CacheWithExpiry<String> createCache(int port) {
        if(cache!=null) {
            ((RequiresShutdown)cache).shutdown();
        }

        cache = new SpyMemcachedCache<>(
                new ElastiCacheCacheConfigBuilder()
                        .setMemcachedHosts("localhost:" + port)
                        .setTimeToLive(Duration.ofSeconds(1))
                        .setUseStaleCache(true)
                        .setStaleCacheAdditionalTimeToLive(Duration.ofSeconds(4))
                        .setProtocol(ConnectionFactoryBuilder.Protocol.TEXT)
                        .setStaleCachePrefix("staleprefix")
                        .setWaitForMemcachedSet(true)
                        .setMetricsRecorder(new YammerMetricsRecorder(registry))
                        .buildMemcachedConfig()
        );

        return cache;
    }

    @After
    public void tearDown() {
        if(memcached!=null) {
            memcached.getDaemon().stop();
        }

        if(cache!=null && cache instanceof RequiresShutdown) {
            ((RequiresShutdown) cache).shutdown();
        }

        reporter.close();

        executorService.shutdownNow();

    }




    @Test
    public void testMemcachedCache() throws InterruptedException {
//        MetricRegistry registry = new MetricRegistry();
//        final ConsoleReporter reporter = ConsoleReporter.forRegistry(registry)
//                .convertRatesTo(TimeUnit.SECONDS)
//                .convertDurationsTo(TimeUnit.MILLISECONDS)
//                .build();
//
//        cache = new SpyMemcachedCache<>(
//                new ElastiCacheCacheConfigBuilder()
//                        .setMemcachedHosts("localhost:" + memcached.getPort())
//                        .setTimeToLive(Duration.ofSeconds(1))
//                        .setUseStaleCache(true)
//                        .setStaleCacheAdditionalTimeToLive(Duration.ofSeconds(4))
//                        .setProtocol(ConnectionFactoryBuilder.Protocol.TEXT)
//                        .setStaleCachePrefix("staleprefix")
//                        .setWaitForMemcachedSet(true)
//                        .setMetricsRecorder(new YammerMetricsRecorder(registry))
//                        .buildMemcachedConfig()
//        );

        ListenableFuture<String> val = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stale";
        }, executorService);

        assertEquals("Value should be key1","will be stale",cache.awaitForFutureOrElse(val, null));

        Thread.sleep(2500);


        ListenableFuture<String> val2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);


        ListenableFuture<String> val3 = cache.apply("Key1", () -> {
            return "kjlkjlkj";
        }, executorService);


        assertEquals("Value should be key1","will be stale",cache.awaitForFutureOrElse(val3, null));

        Thread.sleep(2500);

        assertEquals("Value should be key1","will not generate",cache.awaitForFutureOrElse(val2, null));

        reporter.report();
        reporter.stop();
        assertTrue(registry.getNames().contains("stale_distributed_cache_hitrate"));


        System.out.println(registry.getNames());
    }

    @Test
    public void testMemcachedCacheFunctionsWhenHostsNotAvailable() throws InterruptedException {

        cache = createCache(11111);

        ListenableFuture<String> val = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stale";
        }, executorService);

        assertEquals("Value should be key1","will be stale",cache.awaitForFutureOrElse(val, null));

        Thread.sleep(2500);


        ListenableFuture<String> val2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);


        ListenableFuture<String> val3 = cache.apply("Key1", () -> {
            return "kjlkjlkj";
        }, executorService);


        assertEquals("Value should be key1","will not generate",cache.awaitForFutureOrElse(val3, null));

        Thread.sleep(2500);

        assertEquals("Value should be key1","will not generate",cache.awaitForFutureOrElse(val2, null));



    }

    @Test
    public void testStaleMemcachedCacheWithRemove() throws InterruptedException {

//        cache = new SpyMemcachedCache<>(
//                new ElastiCacheCacheConfigBuilder()
//                        .setMemcachedHosts("localhost:" + memcached.getPort())
//                        .setTimeToLive(Duration.ofSeconds(1))
//                        .setUseStaleCache(true)
//                        .setStaleCacheAdditionalTimeToLive(Duration.ofSeconds(4))
//                        .setProtocol(ConnectionFactoryBuilder.Protocol.TEXT)
//                        .setWaitForMemcachedSet(true)
//                        .setKeyHashType(KeyHashingType.NONE)
//                        .buildMemcachedConfig()
//        );

        ListenableFuture<String> val = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stalelkjlkjlkjlk";
        }, executorService);

        assertEquals("Value should be key1", "will be stalelkjlkjlkjlk", cache.awaitForFutureOrElse(val, null));

        Thread.sleep(2000);

        ListenableFuture<String> val_again = cache.apply("Key1", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);

        ListenableFuture<String> val_again2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate over and over";
        }, executorService);


        assertEquals("Value should be key1", "will not generate", cache.awaitForFutureOrElse(val_again, null));
        assertEquals("Value should be key1", "will be stalelkjlkjlkjlk", cache.awaitForFutureOrElse(val_again2, null));

        Thread.sleep(10000);


        ListenableFuture<String> val_again3 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate over and over";
        }, executorService);


        assertEquals("Value should be key1", "will not generate over and over", cache.awaitForFutureOrElse(val_again3, null));
        Thread.sleep(3000);

        ListenableFuture<String> a = cache.apply("Key1", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will generate new val";
        }, executorService);

        assertEquals("Value should be key1", "will generate new val", cache.awaitForFutureOrElse(a, null));

        Thread.sleep(2000);

        ListenableFuture<String> b = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will generate this value";
        }, executorService);



        ListenableFuture<String> c = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate this value";
        }, executorService);

        Thread.sleep(300);


        ((ClearableCache)cache).clear("Key1");

        ListenableFuture<String> d = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "newkey";
        }, executorService);



        assertEquals("Value should be key1", "will generate this value", cache.awaitForFutureOrElse(b, null));
        assertEquals("Value should be key1", "will generate new val", cache.awaitForFutureOrElse(c, null));
        assertEquals("Value should be key1", "newkey", cache.awaitForFutureOrElse(d, null));



        Thread.sleep(1500);
    }

    @Test
    public void testStaleMemcachedCache() throws InterruptedException {

//        cache = new SpyMemcachedCache<>(
//                new ElastiCacheCacheConfigBuilder()
//                        .setMemcachedHosts("localhost:" + memcached.getPort())
//                        .setTimeToLive(Duration.ofSeconds(1))
//                        .setUseStaleCache(true)
//                        .setStaleCacheAdditionalTimeToLive(Duration.ofSeconds(4))
//                        .setProtocol(ConnectionFactoryBuilder.Protocol.TEXT)
//                        .setWaitForMemcachedSet(true)
//                        .setKeyHashType(KeyHashingType.NONE)
//                        .buildMemcachedConfig()
//        );

        ListenableFuture<String> val = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stale";
        }, executorService);

        assertEquals("Value should be key1", "will be stale", cache.awaitForFutureOrElse(val, null));

        Thread.sleep(2000);

        ListenableFuture<String> val_again = cache.apply("Key1", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);

        ListenableFuture<String> val_again2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate over and over";
        }, executorService);


        assertEquals("Value should be key1", "will not generate", cache.awaitForFutureOrElse(val_again, null));
        assertEquals("Value should be key1", "will be stale", cache.awaitForFutureOrElse(val_again2, null));

        Thread.sleep(6000);


        ListenableFuture<String> val_again3 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate over and over";
        }, executorService);


        assertEquals("Value should be key1", "will not generate over and over", cache.awaitForFutureOrElse(val_again3, null));
        Thread.sleep(3000);

        ListenableFuture<String> a = cache.apply("Key1", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will generate new val (for stale)";
        }, executorService);

        assertEquals("Value should be key1", "will generate new val (for stale)", cache.awaitForFutureOrElse(a, null));

        Thread.sleep(2500);

        ListenableFuture<String> b = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will generate this value";
        }, executorService);



        ListenableFuture<String> c = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate this value";
        }, executorService);

        Thread.sleep(300);


        ((ClearableCache)cache).clear("Key1");

        ListenableFuture<String> d = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "newkey (in stale)";
        }, executorService);

        Thread.sleep(2500);

        ListenableFuture<String> ef = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "newkey";
        }, executorService);

        ListenableFuture<String> f = cache.apply("Key1", () -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "newkey (will not be used)";
        }, executorService);



        assertEquals("Value should be key1", "will generate this value", cache.awaitForFutureOrElse(b, null));
        assertEquals("Value should be key1", "will generate new val (for stale)", cache.awaitForFutureOrElse(c, null));
        assertEquals("Value should be key1", "newkey (in stale)", cache.awaitForFutureOrElse(d, null));
        assertEquals("Value should be key1", "newkey", cache.awaitForFutureOrElse(ef, null));
        assertEquals("Value should be key1", "newkey (in stale)", cache.awaitForFutureOrElse(f, null));




        Thread.sleep(1500);
    }


    @Test
    public void testStaleMemcachedCacheWithNoSuchItems() throws InterruptedException {

//        cache = new SpyMemcachedCache<>(
//                new ElastiCacheCacheConfigBuilder()
//                        .setMemcachedHosts("localhost:" + memcached.getPort())
//                        .setTimeToLive(Duration.ofSeconds(1))
//                        .setUseStaleCache(true)
//                        .setStaleCacheAdditionalTimeToLive(Duration.ofSeconds(4))
//                        .setProtocol(ConnectionFactoryBuilder.Protocol.TEXT)
//                        .setWaitForMemcachedSet(true)
//                        .setKeyHashType(KeyHashingType.NONE)
//                        .buildMemcachedConfig()
//        );

        ListenableFuture<String> val = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stale";
        }, executorService);

        assertEquals("Value should be key1","will be stale",cache.awaitForFutureOrElse(val, null));

        ListenableFuture<String> val_again = cache.apply("Key1", () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);

        assertEquals("Value should be key1","will be stale",cache.awaitForFutureOrElse(val_again, null));

        Thread.sleep(1500);

        memcached.getDaemon().getCache().flush_all();

        ListenableFuture<String> val2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);


        ListenableFuture<String> val3 = cache.apply("Key1", () -> {
            return "kjlkjlkj";
        }, executorService);


        assertEquals("Value should be key1","will not generate",cache.awaitForFutureOrElse(val3, null));


        Thread.sleep(2500);

        assertEquals("Value should be key1","will not generate",cache.awaitForFutureOrElse(val2, null));

        ListenableFuture<String> valStaleEntry = cache.apply("Key1", () -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will be stale entry";
        }, executorService);


        assertEquals("Value should be key1","will be stale entry",cache.awaitForFutureOrElse(valStaleEntry, null));

        Thread.sleep(2000);

        ListenableFuture<String> valStaleEntry2 = cache.apply("Key1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return "will not generate";
        }, executorService);

        assertEquals("Value should be key1","will be stale entry",cache.awaitForFutureOrElse(cache.get("Key1"), null));

    }
}

package org.greencheek.caching.herdcache.lru.expiry;

import com.google.common.util.concurrent.*;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.greencheek.caching.herdcache.Cache;

import java.io.Serializable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Created by dominictootell on 28/07/2014.
 */
public class ExpiringLastRecentlyUsedCache<V extends Serializable> implements Cache<V> {

    private enum TimedEntryType { TTL_ONLY, TTL_WITH_IDLE}

    private final ConcurrentMap<String,TimedEntry<V>> store;
    private final ExpiryTimes expiryTimes;
    private final TimedEntryType timedEntryType;

    private final CacheValueAndEntryComputationFailureHandler failureHandler;


    public ExpiringLastRecentlyUsedCache(long maxCapacity,int initialCapacity,
                                         long timeToLive, long timeToIdle, TimeUnit timeUnit) {
        expiryTimes = new ExpiryTimes(timeToIdle,timeToLive,timeUnit);

        if(timeToLive<1) {
            throw new InstantiationError("Time To Live must be greater than 0");
        }

        if(timeToIdle == 0) {
            timedEntryType = TimedEntryType.TTL_ONLY;
        } else {
            timedEntryType = TimedEntryType.TTL_WITH_IDLE;
        }

        store =  new ConcurrentLinkedHashMap.Builder<String, TimedEntry<V>>()
                .initialCapacity(initialCapacity)
                .maximumWeightedCapacity(maxCapacity)
                .build();

        failureHandler = (String key,TimedEntry entry, Throwable t) -> { store.remove(key,entry); };
    }


    @Override
    public ListenableFuture<V> apply(String key, Supplier<V> computation, ListeningExecutorService executorService) {
        TimedEntry<V> value = store.get(key);
        if(value==null) {
            return insertTimedEntry(key,computation,executorService);
        } else {
            if(value.hasNotExpired(expiryTimes)) {
                value.touch();
                return value.getFuture();
            } else {
                return insertTimedEntry(key,computation,executorService);
            }
        }
    }

    private TimedEntry<V> createTimedEntry(SettableFuture<V> future) {
        TimedEntry<V> entry;
        switch (timedEntryType) {
            case TTL_WITH_IDLE:
                entry = new IdleTimedEntryWithExpiry<>(future);
                break;
            default:
                entry = new IdleTimedEntryWithExpiry<>(future);
                break;
        }
        return entry;
    }

    private ListenableFuture<V>  insertTimedEntry(String key, Supplier<V> computation, ListeningExecutorService executorService) {
        SettableFuture<V> toBeComputedFuture =  SettableFuture.create();
        TimedEntry<V> newEntry = createTimedEntry(toBeComputedFuture);

        FutureCallback<V> callback = new CacheEntryRequestFutureComputationCompleteNotifier<V>(key,newEntry, toBeComputedFuture, failureHandler);

        TimedEntry<V> previousTimedEntry = store.put(key, newEntry);
        if(previousTimedEntry==null) {
            ListenableFuture<V> computationFuture = executorService.submit(() -> computation.get());
            Futures.addCallback(computationFuture, callback);
            return newEntry.getFuture();
        }
        else {
            if(previousTimedEntry.hasNotExpired(expiryTimes)) {
                newEntry.setCreatedAt(previousTimedEntry.getCreatedAt());
                newEntry.touch();
                Futures.addCallback(previousTimedEntry.getFuture(),callback);

            } else {
                ListenableFuture<V> computationFuture = executorService.submit(() -> computation.get());
                Futures.addCallback(computationFuture,callback);
            }
            return newEntry.getFuture();
        }
    }


}

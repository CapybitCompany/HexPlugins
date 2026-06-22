package hex.limbo.premium;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caches {@link PremiumResolver} results with a TTL so we don't hammer the Mojang API. Only
 * definite answers ({@link Result.Status#PREMIUM} and {@link Result.Status#NOT_PREMIUM}) are
 * cached – {@link Result.Status#UNKNOWN} is intentionally not stored so the next caller will
 * re-attempt the upstream check (i.e. it never "sticks" during an outage).
 */
public final class CachedPremiumResolver implements PremiumResolver {

    private final PremiumResolver delegate;
    private final long ttlMillis;
    private final int maxEntries;
    private final LinkedHashMap<String, CachedEntry> cache;
    private final ReentrantLock lock = new ReentrantLock();

    public CachedPremiumResolver(PremiumResolver delegate, long ttlSeconds, int maxEntries) {
        this.delegate = delegate;
        this.ttlMillis = Math.max(1_000L, ttlSeconds * 1_000L);
        this.maxEntries = Math.max(16, maxEntries);
        this.cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                return size() > CachedPremiumResolver.this.maxEntries;
            }
        };
    }

    @Override
    public Result resolve(String username) {
        if (username == null || username.isBlank()) {
            return Result.notPremium();
        }
        String key = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            CachedEntry hit = cache.get(key);
            if (hit != null && hit.expiresAt > now) {
                return hit.result;
            }
            cache.remove(key);
        } finally {
            lock.unlock();
        }
        Result fresh = delegate.resolve(username);
        if (fresh.isUnknown()) {
            return fresh;
        }
        lock.lock();
        try {
            cache.put(key, new CachedEntry(fresh, now + ttlMillis));
        } finally {
            lock.unlock();
        }
        return fresh;
    }

    public void invalidate(String username) {
        if (username == null) {
            return;
        }
        lock.lock();
        try {
            cache.remove(username.toLowerCase(Locale.ROOT));
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    public int cacheSize() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    private record CachedEntry(Result result, long expiresAt) {}
}

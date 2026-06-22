package hex.limbo.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sliding-window rate limiter. Each key (username or IP hash) gets its own window of attempt
 * timestamps. Old timestamps are pruned on each call.
 */
public final class RateLimiter {

    private final int maxAttempts;
    private final long windowMillis;
    private final Map<String, Deque<Long>> attempts = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public RateLimiter(int maxAttempts, long windowMillis) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0");
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be > 0");
        }
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowMillis;
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    public boolean tryAcquire(String key, long nowMillis) {
        if (key == null) {
            return true;
        }
        lock.lock();
        try {
            Deque<Long> queue = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
            long cutoff = nowMillis - windowMillis;
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.removeFirst();
            }
            if (queue.size() >= maxAttempts) {
                return false;
            }
            queue.addLast(nowMillis);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void reset(String key) {
        lock.lock();
        try {
            attempts.remove(key);
        } finally {
            lock.unlock();
        }
    }
}

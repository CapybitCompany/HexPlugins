package hex.limbo.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void allowsUpToLimit() {
        RateLimiter limiter = new RateLimiter(3, 60_000L);
        assertTrue(limiter.tryAcquire("user", 0L));
        assertTrue(limiter.tryAcquire("user", 10L));
        assertTrue(limiter.tryAcquire("user", 20L));
        assertFalse(limiter.tryAcquire("user", 30L));
    }

    @Test
    void recoversAfterWindow() {
        RateLimiter limiter = new RateLimiter(2, 1_000L);
        assertTrue(limiter.tryAcquire("user", 0L));
        assertTrue(limiter.tryAcquire("user", 100L));
        assertFalse(limiter.tryAcquire("user", 200L));
        assertTrue(limiter.tryAcquire("user", 1_500L));
    }

    @Test
    void perKeyTracking() {
        RateLimiter limiter = new RateLimiter(1, 60_000L);
        assertTrue(limiter.tryAcquire("user-a", 0L));
        assertTrue(limiter.tryAcquire("user-b", 0L));
        assertFalse(limiter.tryAcquire("user-a", 100L));
    }
}

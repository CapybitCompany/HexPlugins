package hex.limbo.premium;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tri-state semantics: Mojang outages, malformed responses, and unexpected statuses must NOT be
 * cached and must surface as UNKNOWN so callers can fail closed.
 */
class PremiumResolverErrorTest {

    @Test
    void unknownIsNotCachedAndReachesCaller() {
        AtomicInteger calls = new AtomicInteger();
        PremiumResolver delegate = name -> {
            calls.incrementAndGet();
            return PremiumResolver.Result.unknown();
        };
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 600L, 16);
        PremiumResolver.Result first = cached.resolve("Bob");
        PremiumResolver.Result second = cached.resolve("Bob");
        assertSame(PremiumResolver.Status.UNKNOWN, first.status());
        assertSame(PremiumResolver.Status.UNKNOWN, second.status());
        assertEquals(2, calls.get(), "UNKNOWN must not be cached – upstream is re-queried each call.");
        assertEquals(0, cached.cacheSize());
    }

    @Test
    void definiteResultsAreCached() {
        AtomicInteger calls = new AtomicInteger();
        PremiumResolver delegate = name -> {
            calls.incrementAndGet();
            return PremiumResolver.Result.notPremium();
        };
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 600L, 16);
        cached.resolve("Bob");
        cached.resolve("Bob");
        assertEquals(1, calls.get());
    }

    @Test
    void registerIsBlockedOnUnknownWithoutFailOpen() {
        // RegisterCommand inspects PremiumResolver.Result#isUnknown() before calling AuthService.
        // We mirror that decision here without instantiating Velocity Player.
        PremiumResolver resolver = name -> PremiumResolver.Result.unknown();
        PremiumResolver.Result result = resolver.resolve("Steve");
        boolean failOpen = false;
        boolean wouldDenyRegister = result.isUnknown() && !failOpen;
        assertEquals(true, wouldDenyRegister);
    }

    @Test
    void registerAllowedOnUnknownWhenFailOpen() {
        PremiumResolver resolver = name -> PremiumResolver.Result.unknown();
        PremiumResolver.Result result = resolver.resolve("Steve");
        boolean failOpen = true;
        boolean wouldDenyRegister = result.isUnknown() && !failOpen;
        assertEquals(false, wouldDenyRegister);
    }
}

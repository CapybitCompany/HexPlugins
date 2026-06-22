package hex.limbo.premium;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PremiumResolverHandleTest {

    private CachedPremiumResolver counting(AtomicInteger counter, PremiumResolver.Result result) {
        PremiumResolver delegate = name -> {
            counter.incrementAndGet();
            return result;
        };
        return new CachedPremiumResolver(delegate, 60L, 16);
    }

    @Test
    void resolveDelegatesToInitialResolver() {
        AtomicInteger counter = new AtomicInteger();
        PremiumResolverHandle handle = new PremiumResolverHandle(counting(counter, PremiumResolver.Result.notPremium()));
        PremiumResolver.Result result = handle.resolve("alice");
        assertSame(PremiumResolver.Status.NOT_PREMIUM, result.status());
        assertEquals(1, counter.get());
    }

    @Test
    void swapReplacesActiveResolverAndClearsOldCache() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        CachedPremiumResolver original = counting(first, PremiumResolver.Result.notPremium());
        PremiumResolverHandle handle = new PremiumResolverHandle(original);
        handle.resolve("alice");
        assertEquals(1, first.get());

        CachedPremiumResolver replacement = counting(second, PremiumResolver.Result.premium(null, "alice"));
        handle.swap(replacement);
        PremiumResolver.Result postSwap = handle.resolve("alice");
        assertSame(PremiumResolver.Status.PREMIUM, postSwap.status());
        assertEquals(1, first.get(), "Old resolver must not be invoked after swap.");
        assertEquals(1, second.get(), "Replacement resolver handles new calls.");
        assertEquals(0, original.cacheSize(), "Old cache is wiped during swap.");
    }

    @Test
    void clearWipesCurrentCache() {
        AtomicInteger counter = new AtomicInteger();
        CachedPremiumResolver underlying = counting(counter, PremiumResolver.Result.notPremium());
        PremiumResolverHandle handle = new PremiumResolverHandle(underlying);
        handle.resolve("alice");
        handle.resolve("bob");
        assertEquals(2, underlying.cacheSize());
        handle.clear();
        assertEquals(0, underlying.cacheSize());
    }

    @Test
    void resolveOnNullDelegateReturnsUnknown() {
        PremiumResolverHandle handle = new PremiumResolverHandle(null);
        assertSame(PremiumResolver.Status.UNKNOWN, handle.resolve("alice").status());
    }
}

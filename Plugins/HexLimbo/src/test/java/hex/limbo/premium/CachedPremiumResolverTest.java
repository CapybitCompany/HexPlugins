package hex.limbo.premium;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachedPremiumResolverTest {

    @Test
    void resolverIsConsultedOncePerWindow() {
        AtomicInteger calls = new AtomicInteger(0);
        PremiumResolver delegate = name -> {
            calls.incrementAndGet();
            return new PremiumResolver.Result(
                    PremiumResolver.Status.PREMIUM,
                    Optional.of(UUID.nameUUIDFromBytes(name.getBytes())),
                    Optional.of(name));
        };
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 60L, 16);
        cached.resolve("Notch");
        cached.resolve("notch");
        cached.resolve("NOTCH");
        assertEquals(1, calls.get(), "Lookup should be cached after the first call (case-insensitive).");
    }

    @Test
    void differentNamesHitDelegate() {
        AtomicInteger calls = new AtomicInteger(0);
        PremiumResolver delegate = name -> {
            calls.incrementAndGet();
            return PremiumResolver.Result.notPremium();
        };
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 60L, 16);
        cached.resolve("alice");
        cached.resolve("bob");
        cached.resolve("carl");
        assertEquals(3, calls.get());
    }

    @Test
    void invalidateClearsEntry() {
        AtomicInteger calls = new AtomicInteger(0);
        PremiumResolver delegate = name -> {
            calls.incrementAndGet();
            return PremiumResolver.Result.notPremium();
        };
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 60L, 16);
        cached.resolve("alice");
        cached.invalidate("alice");
        cached.resolve("alice");
        assertEquals(2, calls.get());
    }

    @Test
    void clearEmptiesCache() {
        PremiumResolver delegate = name -> PremiumResolver.Result.notPremium();
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 60L, 32);
        cached.resolve("alice");
        cached.resolve("bob");
        cached.clear();
        assertEquals(0, cached.cacheSize());
    }

    @Test
    void cachePopulatesEntries() {
        PremiumResolver delegate = name -> PremiumResolver.Result.notPremium();
        CachedPremiumResolver cached = new CachedPremiumResolver(delegate, 60L, 32);
        for (int i = 0; i < 10; i++) {
            cached.resolve("player" + i);
        }
        assertTrue(cached.cacheSize() >= 10);
    }
}

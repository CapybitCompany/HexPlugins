package hex.limbo.premium;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic-reference wrapper around the active {@link CachedPremiumResolver}. Listeners and
 * commands hold this handle for the lifetime of the plugin and call through {@link #resolve}
 * normally. When {@code /hexlimbo reload} detects a change in
 * {@code premium.cache-ttl-seconds}, {@code premium.cache-max-entries}, or
 * {@code premium.http-timeout-ms}, the plugin builds a fresh resolver and {@link #swap}s it in.
 *
 * <p>This lets us hot-reload premium HTTP/cache tuning without rewiring every listener and
 * command, while keeping the underlying resolver immutable.
 */
public final class PremiumResolverHandle implements PremiumResolver {

    private final AtomicReference<CachedPremiumResolver> current;

    public PremiumResolverHandle(CachedPremiumResolver initial) {
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public Result resolve(String username) {
        CachedPremiumResolver resolver = current.get();
        if (resolver == null) {
            return Result.unknown();
        }
        return resolver.resolve(username);
    }

    public void swap(CachedPremiumResolver newResolver) {
        CachedPremiumResolver old = current.getAndSet(newResolver);
        if (old != null) {
            old.clear();
        }
    }

    public void clear() {
        CachedPremiumResolver resolver = current.get();
        if (resolver != null) {
            resolver.clear();
        }
    }

    public void invalidate(String username) {
        CachedPremiumResolver resolver = current.get();
        if (resolver != null) {
            resolver.invalidate(username);
        }
    }

    public int cacheSize() {
        CachedPremiumResolver resolver = current.get();
        return resolver == null ? 0 : resolver.cacheSize();
    }
}

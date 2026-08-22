package hex.towns.api;

import java.util.concurrent.CompletableFuture;

/** Full-context town purge handler. Prefer this API for all town-owned persistence. */
@FunctionalInterface
public interface TownDataResetHandlerV2 {
    CompletableFuture<Void> purgeTown(TownPurgeContext context);
}

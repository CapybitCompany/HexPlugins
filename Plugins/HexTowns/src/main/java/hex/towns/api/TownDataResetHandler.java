package hex.towns.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface TownDataResetHandler {
    CompletableFuture<Void> purgeTown(UUID townId, List<UUID> members);
}
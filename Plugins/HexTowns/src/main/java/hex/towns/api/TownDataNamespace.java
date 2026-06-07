package hex.towns.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface TownDataNamespace {
    String namespace();

    CompletableFuture<Void> purgeTown(UUID townId);
}
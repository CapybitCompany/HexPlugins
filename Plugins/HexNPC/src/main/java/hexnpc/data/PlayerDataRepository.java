package hexnpc.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PlayerDataRepository {
    CompletableFuture<Void> init();
    CompletableFuture<Map<String, String>> load(UUID playerId);
    CompletableFuture<Void> set(UUID playerId, String key, String value);
    CompletableFuture<Void> delete(UUID playerId, String key);
    boolean available();
}

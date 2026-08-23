package hexnpc.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerDataServiceTest {

    @Test
    void synchronousBackendDoesNotCauseRecursiveConcurrentHashMapUpdate() {
        MemoryRepository repository = new MemoryRepository();
        PlayerDataService service = new PlayerDataService(repository, Logger.getLogger("test"));
        service.init().join();
        assertTrue(service.ready());

        UUID player = UUID.randomUUID();
        service.ensureLoaded(player).join();
        service.set(player, "cosmetics.custom_tag", "Król Kasyna").join();
        assertEquals("Król Kasyna", service.getCached(player, "cosmetics.custom_tag"));

        service.unload(player);
        service.ensureLoaded(player).join();
        assertEquals("Król Kasyna", service.getCached(player, "cosmetics.custom_tag"));

        service.delete(player, "cosmetics.custom_tag").join();
        assertFalse(service.hasCached(player, "cosmetics.custom_tag"));
    }

    private static final class MemoryRepository implements PlayerDataRepository {
        private final Map<UUID, Map<String, String>> values = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Void> init() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Map<String, String>> load(UUID playerId) {
            return CompletableFuture.completedFuture(Map.copyOf(
                    values.getOrDefault(playerId, Map.of())));
        }

        @Override
        public CompletableFuture<Void> set(UUID playerId, String key, String value) {
            values.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                    .put(key, value == null ? "" : value);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> delete(UUID playerId, String key) {
            Map<String, String> player = values.get(playerId);
            if (player != null) player.remove(key);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}

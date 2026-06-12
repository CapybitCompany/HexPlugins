package pl.hex.abovename.storage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence abstraction for HexAboveName titles.
 *
 * Implementations may complete futures synchronously (YAML) or asynchronously
 * (database). Callers MUST never assume which one — they must always treat
 * results as potentially async and never block the Bukkit main thread.
 */
public interface TitleStorage {

    /** Loads every title. Used at startup/reload to populate the in-memory cache. */
    CompletableFuture<Map<UUID, StoredTitle>> loadAll();

    /** Loads a single title by UUID. */
    CompletableFuture<Optional<StoredTitle>> load(UUID uuid);

    /** Saves or upserts a title. */
    CompletableFuture<Void> save(UUID uuid, String playerName, String title);

    /** Deletes a title by UUID. No-op if missing. */
    CompletableFuture<Void> delete(UUID uuid);

    /** Case-insensitive name lookup. */
    CompletableFuture<Optional<UUID>> findUuidByName(String name);

    /** Optional one-shot schema bootstrap. Returns immediately for storages that need none. */
    default CompletableFuture<Void> ensureSchema() {
        return CompletableFuture.completedFuture(null);
    }

    /** Optional shutdown hook. */
    default void close() {
    }
}

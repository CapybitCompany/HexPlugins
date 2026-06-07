package hex.towns.service;

import hex.core.api.HexApi;
import hex.towns.api.TownDataNamespace;
import hex.towns.api.TownDataResetHandler;
import hex.towns.database.TownRepository;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TownDataRegistry {
    private static final Logger LOG = Logger.getLogger("HexTowns-DataRegistry");

    private final HexApi api;
    private final TownRepository repository;
    private final Map<String, RegisteredNamespace> namespaces = new ConcurrentHashMap<>();

    public TownDataRegistry(HexApi api, TownRepository repository) {
        this.api = api;
        this.repository = repository;
    }

    public TownDataNamespace register(Plugin owner, String namespace, TownDataResetHandler handler) {
        String normalized = normalize(namespace);
        RegisteredNamespace registered = new RegisteredNamespace(normalized, owner.getName(), handler);
        namespaces.put(normalized, registered);
        api.db().asyncRun(() -> repository.upsertNamespace(normalized, owner.getName()));
        LOG.info("Registered town data namespace '" + normalized + "' for plugin " + owner.getName());
        return registered;
    }

    public List<String> namespaces() {
        return List.copyOf(namespaces.keySet());
    }

    public CompletableFuture<Void> purgeTown(UUID townId, List<UUID> members) {
        CompletableFuture<?>[] tasks = namespaces.values().stream()
                .map(namespace -> purgeNamespace(namespace, townId, members))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(tasks);
    }

    private CompletableFuture<Void> purgeNamespace(RegisteredNamespace namespace, UUID townId, List<UUID> members) {
        LOG.info("Purging town data namespace '" + namespace.namespace + "' (plugin=" + namespace.ownerName
                + ", townId=" + townId + ", members=" + members.size() + ")");

        CompletableFuture<Void> purge;
        try {
            purge = namespace.handler.purgeTown(townId, List.copyOf(members));
        } catch (Throwable throwable) {
            LOG.log(Level.SEVERE, "Town data namespace '" + namespace.namespace + "' failed before async purge started", throwable);
            return CompletableFuture.failedFuture(throwable);
        }

        if (purge == null) {
            IllegalStateException failure = new IllegalStateException(
                    "TownDataResetHandler returned null for namespace " + namespace.namespace);
            LOG.log(Level.SEVERE, failure.getMessage(), failure);
            return CompletableFuture.failedFuture(failure);
        }

        return purge.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                LOG.info("Purged town data namespace '" + namespace.namespace + "' for townId=" + townId);
            } else {
                LOG.log(Level.SEVERE, "Town data namespace '" + namespace.namespace + "' failed purge for townId=" + townId,
                        throwable);
            }
        });
    }

    private static String normalize(String namespace) {
        String cleaned = namespace == null ? "unknown" : namespace.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (cleaned.isBlank()) {
            return "unknown";
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private record RegisteredNamespace(String namespace, String ownerName, TownDataResetHandler handler) implements TownDataNamespace {
        @Override
        public CompletableFuture<Void> purgeTown(UUID townId) {
            return handler.purgeTown(townId, List.of());
        }
    }
}
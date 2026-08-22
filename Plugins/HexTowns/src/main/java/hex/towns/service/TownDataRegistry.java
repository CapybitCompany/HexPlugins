package hex.towns.service;

import hex.core.api.HexApi;
import hex.towns.api.TownDataNamespace;
import hex.towns.api.TownDataResetHandler;
import hex.towns.api.TownDataResetHandlerV2;
import hex.towns.api.TownPurgeContext;
import hex.towns.api.TownPurgeResult;
import hex.towns.database.TownRepository;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
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
        if (handler == null) throw new IllegalArgumentException("handler");
        return registerV2(owner, namespace, context -> handler.purgeTown(context.townUuid(), context.members()));
    }

    public TownDataNamespace registerV2(Plugin owner, String namespace, TownDataResetHandlerV2 handler) {
        if (owner == null) throw new IllegalArgumentException("owner");
        if (handler == null) throw new IllegalArgumentException("handler");
        String normalized = normalize(namespace);
        RegisteredNamespace registered = new RegisteredNamespace(normalized, owner.getName(), owner.getDescription().getVersion(), handler);
        namespaces.put(normalized, registered);
        // Dependent plugins can register immediately after HexTowns.onEnable while the main
        // schema bootstrap is still running asynchronously. Make this tiny persistence path
        // self-initialising so registration cannot be lost in that startup race.
        api.db().asyncRun(() -> {
            repository.ensureDataNamespaceTable();
            repository.upsertNamespace(normalized, owner.getName(), owner.getDescription().getVersion());
        }).exceptionally(error -> {
            LOG.log(Level.WARNING, "Could not persist town data namespace '" + normalized + "'; it remains registered in memory", error);
            return null;
        });
        LOG.info("Registered town data namespace '" + normalized + "' for plugin " + owner.getName());
        return registered;
    }

    /** Active handlers registered in this JVM boot. This is the snapshot source for NEW cleanup jobs. */
    public List<String> namespaces() {
        return List.copyOf(namespaces.keySet());
    }

    public boolean isRegistered(String namespace) {
        return namespaces.containsKey(normalize(namespace));
    }

    /**
     * Compatibility API. Detailed results are returned to TownsService, which persists every
     * namespace result and deliberately blocks CORE_DB when an active dependency fails.
     */
    public CompletableFuture<Void> purgeTown(UUID townId, List<UUID> members) {
        return purgeTownDetailed(TownPurgeContext.compatibility(townId, members)).thenApply(ignored -> null);
    }

    public CompletableFuture<List<TownPurgeResult>> purgeTownDetailed(UUID townId, List<UUID> members) {
        return purgeTownDetailed(TownPurgeContext.compatibility(townId, members));
    }

    public CompletableFuture<List<TownPurgeResult>> purgeTownDetailed(TownPurgeContext context) {
        List<RegisteredNamespace> snapshot = new ArrayList<>(namespaces.values());
        if (snapshot.isEmpty()) return CompletableFuture.completedFuture(List.of());

        List<CompletableFuture<TownPurgeResult>> tasks = snapshot.stream()
                .map(namespace -> purgeNamespace(namespace, context))
                .toList();
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> tasks.stream().map(CompletableFuture::join).toList());
    }

    public CompletableFuture<TownPurgeResult> purgeNamespace(String namespace, UUID townId, List<UUID> members) {
        return purgeNamespace(namespace, TownPurgeContext.compatibility(townId, members));
    }

    public CompletableFuture<TownPurgeResult> purgeNamespace(String namespace, TownPurgeContext context) {
        RegisteredNamespace registered = namespaces.get(normalize(namespace));
        if (registered == null) {
            return CompletableFuture.completedFuture(new TownPurgeResult(normalize(namespace), false, -1, "namespace not registered"));
        }
        return purgeNamespace(registered, context);
    }

    private CompletableFuture<TownPurgeResult> purgeNamespace(RegisteredNamespace namespace, TownPurgeContext context) {
        UUID townId = context == null ? null : context.townUuid();
        int memberCount = context == null ? 0 : context.members().size();
        LOG.info("Purging town data namespace '" + namespace.namespace + "' (plugin=" + namespace.ownerName
                + ", townId=" + townId + ", members=" + memberCount + ")");

        CompletableFuture<Void> purge;
        try {
            purge = namespace.handler.purgeTown(context);
        } catch (Throwable throwable) {
            LOG.log(Level.SEVERE, "Town data namespace '" + namespace.namespace + "' failed before async purge started", throwable);
            return CompletableFuture.completedFuture(TownPurgeResult.failed(namespace.namespace, throwable));
        }

        if (purge == null) {
            IllegalStateException failure = new IllegalStateException(
                    "TownDataResetHandlerV2 returned null for namespace " + namespace.namespace);
            LOG.log(Level.SEVERE, failure.getMessage(), failure);
            return CompletableFuture.completedFuture(TownPurgeResult.failed(namespace.namespace, failure));
        }

        return purge.orTimeout(30, java.util.concurrent.TimeUnit.SECONDS).handle((ignored, throwable) -> {
            if (throwable == null) {
                LOG.info("Purged town data namespace '" + namespace.namespace + "' for townId=" + townId);
                return TownPurgeResult.ok(namespace.namespace);
            }
            LOG.log(Level.SEVERE, "Town data namespace '" + namespace.namespace + "' failed purge for townId=" + townId,
                    throwable);
            return TownPurgeResult.failed(namespace.namespace, throwable);
        });
    }

    private static String normalize(String namespace) {
        String cleaned = namespace == null ? "unknown" : namespace.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (cleaned.isBlank()) {
            return "unknown";
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private record RegisteredNamespace(String namespace, String ownerName, String ownerVersion, TownDataResetHandlerV2 handler) implements TownDataNamespace {
        @Override
        public CompletableFuture<Void> purgeTown(UUID townId) {
            CompletableFuture<Void> purge;
            try {
                purge = handler.purgeTown(TownPurgeContext.compatibility(townId, List.of()));
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
            return purge == null ? CompletableFuture.failedFuture(new IllegalStateException("TownDataResetHandlerV2 returned null")) : purge;
        }
    }
}

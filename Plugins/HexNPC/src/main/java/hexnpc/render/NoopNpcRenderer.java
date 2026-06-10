package hexnpc.render;

import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Fallback renderer used when no packet backend is available
 * (PacketEvents missing, tests, etc.). Tracks NPCs in memory so the
 * rest of the plugin behaves correctly; nothing is sent to clients.
 */
public final class NoopNpcRenderer implements NpcRenderer {

    private final Logger logger;
    private final boolean debug;
    private final Map<NpcId, Handle> bySupervisor = new HashMap<>();
    private final Map<Integer, NpcId> byEntityId = new HashMap<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(900_000);

    public NoopNpcRenderer(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    public void start() {
        if (debug) {
            logger.info("HexNPC: noop renderer started (no NPCs will be visible).");
        }
    }

    @Override
    public void stop() {
        bySupervisor.clear();
        byEntityId.clear();
    }

    @Override
    public NpcHandle spawn(NpcDefinition definition) {
        Handle existing = bySupervisor.get(definition.id());
        if (existing != null) {
            return existing;
        }
        int entityId = nextEntityId.getAndIncrement();
        Handle handle = new Handle(definition.id(), entityId);
        bySupervisor.put(definition.id(), handle);
        byEntityId.put(entityId, definition.id());
        if (debug) {
            logger.info("HexNPC: noop spawn " + definition.id() + " -> eid " + entityId);
        }
        return handle;
    }

    @Override
    public void despawn(NpcId id) {
        Handle removed = bySupervisor.remove(id);
        if (removed != null) {
            byEntityId.remove(removed.entityId());
        }
    }

    @Override
    public void move(NpcDefinition updated) {
        // no-op
    }

    @Override
    public void rotate(NpcDefinition updated) {
        // no-op
    }

    @Override
    public void showTo(Player player) {
        // no-op
    }

    @Override
    public void hideFrom(Player player) {
        // no-op
    }

    @Override
    public Optional<NpcHandle> handle(NpcId id) {
        return Optional.ofNullable(bySupervisor.get(id));
    }

    @Override
    public Optional<NpcId> lookupByEntityId(int entityId) {
        return Optional.ofNullable(byEntityId.get(entityId));
    }

    private record Handle(NpcId id, int entityId) implements NpcHandle {
    }
}

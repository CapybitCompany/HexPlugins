package hexnpc.render.packet;

import hexnpc.model.NpcId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents INTERACT_AT + INTERACT from firing one physical NPC click twice. */
final class NpcClickDebouncer {

    private final long windowNanos;
    private final Map<Key, Long> lastAccepted = new ConcurrentHashMap<>();

    NpcClickDebouncer(long windowMillis) {
        this.windowNanos = Math.max(1L, windowMillis) * 1_000_000L;
    }

    boolean tryAcquire(UUID playerId, NpcId npcId, long nowNanos) {
        Key key = new Key(playerId, npcId);
        final boolean[] accepted = {false};
        lastAccepted.compute(key, (ignored, previous) -> {
            if (previous == null || nowNanos - previous >= windowNanos || nowNanos < previous) {
                accepted[0] = true;
                return nowNanos;
            }
            return previous;
        });
        if (lastAccepted.size() > 4096) {
            long cutoff = nowNanos - (windowNanos * 8L);
            lastAccepted.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        return accepted[0];
    }

    void clear() {
        lastAccepted.clear();
    }

    private record Key(UUID playerId, NpcId npcId) {
    }
}

package hexnpc.service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Short-lived guard used after a packet-only NPC right-click.
 */
public final class NpcItemUseSuppressor {

    static final Duration DEFAULT_WINDOW = Duration.ofMillis(300);

    private final ConcurrentHashMap<UUID, Long> suppressUntilNanos = new ConcurrentHashMap<>();
    private final long windowNanos;
    private final LongSupplier nanoTime;

    public NpcItemUseSuppressor() {
        this(DEFAULT_WINDOW, System::nanoTime);
    }

    NpcItemUseSuppressor(Duration window, LongSupplier nanoTime) {
        Objects.requireNonNull(window, "window");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.windowNanos = Math.max(1L, window.toNanos());
    }

    public void suppress(UUID playerId) {
        if (playerId == null) {
            return;
        }
        long now = nanoTime.getAsLong();
        suppressUntilNanos.put(playerId, now + windowNanos);
    }

    public boolean shouldCancelUse(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        Long until = suppressUntilNanos.get(playerId);
        if (until == null) {
            return false;
        }
        long now = nanoTime.getAsLong();
        if (now > until) {
            suppressUntilNanos.remove(playerId, until);
            return false;
        }
        return true;
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            suppressUntilNanos.remove(playerId);
        }
    }

    public void clearAll() {
        suppressUntilNanos.clear();
    }
}

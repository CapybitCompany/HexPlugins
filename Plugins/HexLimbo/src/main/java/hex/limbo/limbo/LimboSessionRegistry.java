package hex.limbo.limbo;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe map of in-flight {@link LimboSession}s, keyed by player UUID. The internal limbo
 * server adds entries on accept and removes them on disconnect. Future bot-defence code can query
 * this to count concurrent connects per IP, expire stuck sessions, etc.
 */
public final class LimboSessionRegistry {

    private final ConcurrentHashMap<UUID, LimboSession> sessions = new ConcurrentHashMap<>();

    public LimboSession add(LimboSession session) {
        sessions.put(session.uuid(), session);
        return session;
    }

    public void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    public int activeCount() {
        return sessions.size();
    }

    public Collection<LimboSession> snapshot() {
        return Collections.unmodifiableCollection(sessions.values());
    }
}

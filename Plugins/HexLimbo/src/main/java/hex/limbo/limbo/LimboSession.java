package hex.limbo.limbo;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-connection state for a player inside the internal void limbo. v1 stores only the bare
 * minimum needed for accounting; the structure exists so future bot-defence/captcha hooks can
 * track per-player progress, last-seen keep-alive replies, suspicious patterns, etc.
 */
public final class LimboSession {

    public enum Stage {
        CONNECTING,
        IN_VOID,
        DISCONNECTED
    }

    private final UUID uuid;
    private final String username;
    private final long joinedAt;
    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.CONNECTING);
    private volatile long lastKeepAliveSentAt;
    private volatile long lastKeepAliveAckAt;

    public LimboSession(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
        this.joinedAt = System.currentTimeMillis();
    }

    public UUID uuid() { return uuid; }
    public String username() { return username; }
    public long joinedAt() { return joinedAt; }
    public Stage stage() { return stage.get(); }
    public void setStage(Stage next) { stage.set(next); }
    public long lastKeepAliveSentAt() { return lastKeepAliveSentAt; }
    public void recordKeepAliveSent(long at) { this.lastKeepAliveSentAt = at; }
    public long lastKeepAliveAckAt() { return lastKeepAliveAckAt; }
    public void recordKeepAliveAck(long at) { this.lastKeepAliveAckAt = at; }
}

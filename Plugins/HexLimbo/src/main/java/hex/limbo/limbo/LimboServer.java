package hex.limbo.limbo;

import java.util.Optional;

/**
 * Lifecycle abstraction for HexLimbo's internal Minecraft backend. The plugin owns exactly one
 * instance, starts it before listeners are registered, and stops it on shutdown.
 *
 * <p>{@link #isReady()} must be {@code true} before the router will send any player into the
 * limbo; if it is {@code false} the listeners kick the player with
 * {@code disconnect.limbo-unavailable}.
 */
public interface LimboServer {

    /** Bind the backend port and start accepting connections. Idempotent. */
    void start();

    /** Stop accepting new connections, close sessions, release the port. Idempotent. */
    void stop();

    /** True once the port is bound and the server is ready to take Velocity backend connections. */
    boolean isReady();

    /** Configured server name HexLimbo registers itself under in the Velocity proxy. */
    String serverName();

    /** Configured bind host (typically a loopback address). */
    String bindHost();

    /** Configured bind port. */
    int bindPort();

    /** Number of live limbo sessions (players who have passed Login Success). */
    int activeConnectionCount();

    /** Number of accepted TCP connections currently held by the backend, before/after login. */
    int tcpConnectionCount();

    /** Human-readable error from the last failed start, if any. */
    Optional<String> lastStartError();

    /** Access to the session registry for future bot-defence/admin tooling. */
    LimboSessionRegistry sessionRegistry();
}

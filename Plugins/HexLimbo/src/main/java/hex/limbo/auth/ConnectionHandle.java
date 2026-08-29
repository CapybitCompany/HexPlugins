package hex.limbo.auth;

import net.kyori.adventure.audience.Audience;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Identity of one physical player connection, and the anchor for everything that connection owns.
 *
 * <p>A UUID is <em>not</em> an identity: the same account can disconnect and reconnect in
 * milliseconds, and while HexLimbo verifies a password on a worker thread the player behind that
 * UUID may already be a different socket. Every handle therefore carries
 *
 * <ul>
 *     <li>a strictly monotonic {@link #token()} handed out by {@link ConnectionRegistry}, and</li>
 *     <li>a reference to the concrete {@code Player} instance ({@link #isFor(Object)} compares it by
 *     identity, never by UUID),</li>
 * </ul>
 *
 * and every state change is validated against it. An operation that still holds an older handle can
 * be recognised and rejected instead of corrupting the connection that replaced it.
 *
 * <p>The connection's {@link AuthState} hangs off the handle rather than a separate UUID map. That
 * is the structural half of the fix: an old worker physically cannot reach the new connection's
 * auth state, because it only ever has a reference to its own (now unreachable) handle.
 *
 * <p>Handles are cheap value-like objects; the mutable slots ({@link #authState()},
 * {@link #loginTimeout()}) are {@code volatile} and only ever written by the connection's own
 * pipeline.
 *
 * <p>Each handle also carries a monotone {@link #currentOperation()} counter, bumped once per
 * ordered auth operation. It is what lets a result that was produced by an earlier operation be
 * recognised as overtaken - see {@link ConnectionRegistry.OperationStamp} - and, alongside it, the
 * {@link RouteCoordinator.RouteState} that keeps the connection's real backend in step with the
 * newest of those operations.
 */
public final class ConnectionHandle {

    /** Something that can be cancelled, so the auth layer need not know about Velocity's scheduler. */
    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    private final long token;
    private final UUID uuid;
    private final String username;
    private final Object connection;
    private final Audience audience;

    private volatile AuthState authState;
    private volatile Cancellable loginTimeout;
    private final AtomicLong operations = new AtomicLong();
    private final RouteCoordinator.RouteState routeState = new RouteCoordinator.RouteState();

    ConnectionHandle(long token, UUID uuid, String username, Object connection, Audience audience) {
        this.token = token;
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.username = username;
        this.connection = connection;
        this.audience = audience;
    }

    /** Strictly increasing across the proxy's lifetime; never reused, never reordered. */
    public long token() {
        return token;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    /** The connection as an Adventure sink. In production this is the {@code Player} itself. */
    public Audience audience() {
        return audience;
    }

    /**
     * Claims the next ordered operation id for this connection. Called by
     * {@link ConnectionRegistry#enterCommitOrder} while it holds the UUID's commit slot, so the ids
     * are handed out in exactly the order the operations are linearized.
     */
    long beginOperation() {
        return operations.incrementAndGet();
    }

    /**
     * The most recent ordered operation this connection started; {@code 0} before the first one.
     *
     * <p>This is the revision a flow result is checked against before its player-facing effects are
     * allowed to run. A result whose operation is no longer the current one has been overtaken by a
     * later operation on the same connection and must stay silent. The counter lives on the handle
     * and dies with it, so nothing has to be collected or expired.
     */
    public long currentOperation() {
        return operations.get();
    }

    /**
     * This connection's routing bookkeeping, owned by {@link RouteCoordinator}.
     *
     * <p>It hangs off the handle for the same reason the auth state does: a transfer callback that
     * outlives its connection holds <em>this</em> object, so it can neither move the connection
     * that replaced it nor wipe that connection's routing state. Nothing has to be collected or
     * expired either - the state becomes unreachable exactly when the handle does.
     */
    RouteCoordinator.RouteState routeState() {
        return routeState;
    }

    /**
     * True when {@code candidate} is the very {@code Player} instance this handle was opened for.
     * Identity comparison on purpose: two connections of the same account are different objects.
     */
    public boolean isFor(Object candidate) {
        return connection != null && connection == candidate;
    }

    public Optional<AuthState> authState() {
        return Optional.ofNullable(authState);
    }

    void setAuthState(AuthState authState) {
        this.authState = authState;
    }

    /** The login-timeout task this connection scheduled, if any. */
    Optional<Cancellable> loginTimeout() {
        return Optional.ofNullable(loginTimeout);
    }

    void setLoginTimeout(Cancellable loginTimeout) {
        this.loginTimeout = loginTimeout;
    }

    /** Cancels this connection's login timeout, if it had one. Idempotent. */
    void cancelLoginTimeout() {
        Cancellable task = loginTimeout;
        loginTimeout = null;
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isAuthenticated() {
        AuthState state = authState;
        return state != null && state.isAuthenticated();
    }

    @Override
    public String toString() {
        return "ConnectionHandle[token=" + token + ", uuid=" + uuid + ", username=" + username + "]";
    }
}

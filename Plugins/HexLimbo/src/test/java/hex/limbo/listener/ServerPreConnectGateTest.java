package hex.limbo.listener;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static hex.limbo.listener.ServerConnectListener.PreConnectDecision.ALLOW;
import static hex.limbo.listener.ServerConnectListener.PreConnectDecision.DENY_LIMBO_UNAVAILABLE;
import static hex.limbo.listener.ServerConnectListener.PreConnectDecision.REDIRECT_TO_LIMBO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code ServerPreConnectEvent} gate, which is what stands between an unauthenticated socket and
 * a real backend server.
 *
 * <p>The gate used to ask {@code isAuthenticated(uuid)}: a question about the account, not about the
 * socket the event carries. {@link #anOldSocketMustNotInheritTheAuthStateOfTheReconnectThatReplacedIt()}
 * is the case that breaks - connection A never authenticated, B took the UUID over and logged in,
 * and A's in-flight event would have been waved straight through to the target server on the
 * strength of B's login. It fails against the UUID-only gate and passes against the connection gate.
 *
 * <p>These tests drive the production {@link ServerConnectListener#decidePreConnect} on a real
 * listener instance. Only the two routing facts the Velocity event supplies (is the destination the
 * limbo, is the limbo up) are passed in as values, because the {@code ServerPreConnectEvent} and
 * {@code RegisteredServer} they come from cannot be constructed without a running proxy.
 */
class ServerPreConnectGateTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final RuntimeContext context =
            new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
    private final AuthService authService = new AuthService(
            new InMemoryAccountRepository(),
            new PasswordHasher(4),
            new RateLimiter(10, 60_000L),
            context,
            connections,
            LoggerFactory.getLogger(ServerPreConnectGateTest.class));

    /**
     * The real listener. The router and the prompt service are not reachable from the gate - it
     * takes the routing facts as arguments - and both need a live proxy to build.
     */
    private final ServerConnectListener listener =
            new ServerConnectListener(authService, null, null, context, null);

    private static final boolean NO_BYPASS = false;
    private static final boolean TARGET_IS_A_BACKEND = false;
    private static final boolean TARGET_IS_THE_LIMBO = true;
    private static final boolean LIMBO_UP = true;
    private static final boolean LIMBO_DOWN = false;

    /** Opens a connection and attaches an auth state, as the login pipeline does. */
    private ConnectionHandle track(FakeConnection player, AuthState.Stage stage) {
        ConnectionHandle handle = player.connect(connections);
        authService.trackConnection(handle, new AuthState(
                player.uuid(), player.username(), "ip-hash", stage, AccountType.CRACKED));
        return handle;
    }

    private ServerConnectListener.PreConnectDecision decide(FakeConnection player) {
        return listener.decidePreConnect(
                player.uuid(), player, NO_BYPASS, TARGET_IS_A_BACKEND, LIMBO_UP);
    }

    @Test
    void theCurrentAuthenticatedConnectionReachesTheTarget() {
        FakeConnection player = FakeConnection.of("Ala");
        track(player, AuthState.Stage.AUTHENTICATED_CRACKED);

        assertEquals(ALLOW, decide(player));
    }

    @Test
    void theCurrentUnauthenticatedConnectionIsSentToTheLimbo() {
        FakeConnection player = FakeConnection.of("Bogdan");
        track(player, AuthState.Stage.AWAITING_LOGIN);

        assertEquals(REDIRECT_TO_LIMBO, decide(player));
    }

    @Test
    void aPlayerWithNoRegisteredHandleIsFailedClosed() {
        // Never went through the login pipeline: a denied login, or a reload mid-session. Nothing
        // proves they authenticated, so they do not get past the limbo.
        FakeConnection stranger = FakeConnection.of("Cezary");

        assertEquals(REDIRECT_TO_LIMBO, decide(stranger));
    }

    @Test
    void aForeignPlayerInstanceOnATrackedUuidIsFailedClosed() {
        FakeConnection registered = FakeConnection.of("Dominik");
        track(registered, AuthState.Stage.AUTHENTICATED_CRACKED);
        // Same account, a different object: not the socket the registry knows.
        FakeConnection impostor = new FakeConnection(registered.uuid(), registered.username());

        assertEquals(REDIRECT_TO_LIMBO, decide(impostor));
    }

    @Test
    void aDisconnectedConnectionIsFailedClosed() {
        FakeConnection player = FakeConnection.of("Elzbieta");
        ConnectionHandle handle = track(player, AuthState.Stage.AUTHENTICATED_CRACKED);
        connections.end(handle);

        assertEquals(REDIRECT_TO_LIMBO, decide(player));
    }

    /**
     * The regression this gate exists for. A is unauthenticated, B displaces it and authenticates,
     * and A's {@code ServerPreConnectEvent} arrives late. Under a UUID-only check A would be
     * allowed through on B's auth state.
     */
    @Test
    void anOldSocketMustNotInheritTheAuthStateOfTheReconnectThatReplacedIt() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Franciszek".getBytes());
        FakeConnection first = new FakeConnection(uuid, "Franciszek");
        track(first, AuthState.Stage.AWAITING_LOGIN);

        // The reconnect takes the UUID over and logs in.
        FakeConnection second = new FakeConnection(uuid, "Franciszek");
        track(second, AuthState.Stage.AUTHENTICATED_CRACKED);

        assertEquals(ALLOW, decide(second), "the live connection did authenticate and may pass");
        assertEquals(REDIRECT_TO_LIMBO, decide(first),
                "a displaced socket must never travel on the auth state of the one that replaced it");
        // ...and the account-level view really does say "authenticated", which is why the gate must
        // not be asking it.
        assertEquals(true, authService.isAuthenticated(uuid),
                "precondition: the UUID-only question answers yes, and would have let A through");
    }

    // ------------------------------------------------------------------ unchanged behaviour

    @Test
    void adminBypassStillSkipsTheGateEntirely() {
        FakeConnection staff = FakeConnection.of("Grzegorz");
        // No handle at all, and no auth state: the permission alone decides, as before.
        assertEquals(ALLOW, listener.decidePreConnect(
                staff.uuid(), staff, true, TARGET_IS_A_BACKEND, LIMBO_UP));

        // ...and it still applies to a tracked but unauthenticated connection.
        track(staff, AuthState.Stage.AWAITING_LOGIN);
        assertEquals(ALLOW, listener.decidePreConnect(
                staff.uuid(), staff, true, TARGET_IS_A_BACKEND, LIMBO_UP));
        assertEquals(REDIRECT_TO_LIMBO, listener.decidePreConnect(
                staff.uuid(), staff, NO_BYPASS, TARGET_IS_A_BACKEND, LIMBO_UP),
                "and without the permission the same connection is still gated");
    }

    @Test
    void anUnauthenticatedPlayerHeadedForTheLimboIsLeftAlone() {
        FakeConnection player = FakeConnection.of("Halina");
        track(player, AuthState.Stage.UNREGISTERED);

        assertEquals(ALLOW, listener.decidePreConnect(
                player.uuid(), player, NO_BYPASS, TARGET_IS_THE_LIMBO, LIMBO_UP));
    }

    @Test
    void anUnauthenticatedPlayerIsDisconnectedWhenTheLimboIsDown() {
        FakeConnection player = FakeConnection.of("Irena");
        track(player, AuthState.Stage.AWAITING_LOGIN);

        assertEquals(DENY_LIMBO_UNAVAILABLE, listener.decidePreConnect(
                player.uuid(), player, NO_BYPASS, TARGET_IS_A_BACKEND, LIMBO_DOWN));
    }

    @Test
    void anAuthenticatedPlayerIsUnaffectedByAnUnavailableLimbo() {
        FakeConnection player = FakeConnection.of("Jozef");
        track(player, AuthState.Stage.AUTHENTICATED_CRACKED);

        assertEquals(ALLOW, listener.decidePreConnect(
                player.uuid(), player, NO_BYPASS, TARGET_IS_A_BACKEND, LIMBO_DOWN));
    }
}

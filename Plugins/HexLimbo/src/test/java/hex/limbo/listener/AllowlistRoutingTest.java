package hex.limbo.listener;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the allowlist + routing decision the same way the listeners do: by reading auth state
 * and querying the configured allowlist. We don't construct Velocity events directly since they
 * are not on the test classpath, but the data flow under test matches what the listener uses.
 */
class AllowlistRoutingTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();

    private AuthService authService(RuntimeContext context) {
        return new AuthService(
                new InMemoryAccountRepository(),
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
                connections,
                LoggerFactory.getLogger(AllowlistRoutingTest.class)
        );
    }

    @Test
    void unauthenticatedPlayerOnlyAllowedListCommands() {
        PluginConfig config = TestConfigs.withAllowlist(List.of("login", "register", "limbo"));
        Set<String> allowed = config.allowedCommandsUnauthenticated();

        assertTrue(allowed.contains(CommandListener.headOf("/login secret")));
        assertTrue(allowed.contains(CommandListener.headOf("/REGISTER a b")));
        assertFalse(allowed.contains(CommandListener.headOf("/server lobby")));
    }

    /**
     * The routing predicate the listeners really use: it is asked about the socket, never about the
     * UUID. {@link ServerPreConnectGateTest} covers the cases where the two answers differ.
     */
    @Test
    void unauthenticatedRoutesToLimboAuthenticatedToTarget() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        AuthService service = authService(context);
        FakeConnection player = FakeConnection.of("Alice");
        ConnectionHandle handle = player.connect(connections);
        AuthState state = new AuthState(handle.uuid(), "Alice", "iphash", AuthState.Stage.AWAITING_LOGIN, AccountType.CRACKED);
        service.trackConnection(handle, state);
        assertFalse(connections.isAuthenticatedConnection(player.uuid(), player), "Before login, route to limbo");
        state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);
        assertTrue(connections.isAuthenticatedConnection(player.uuid(), player), "After login, route to target");
    }

    @Test
    void allowlistAndServerNamesChangeAfterReload() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        assertTrue(context.config().allowedCommandsUnauthenticated().contains("login"));
        // Reload narrows the allowlist and renames the limbo/target servers.
        context.update(TestConfigs.withAllowlist(List.of("login")), new MessagesConfig(Map.of()));
        assertTrue(context.config().allowedCommandsUnauthenticated().contains("login"));
        assertFalse(context.config().allowedCommandsUnauthenticated().contains("register"));

        context.update(TestConfigs.withServers("waiting-room", "hub"), new MessagesConfig(Map.of()));
        assertTrue(context.config().limboServer().equals("waiting-room"));
        assertTrue(context.config().targetServer().equals("hub"));
    }
}

package hex.limbo.listener;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the allowlist + routing decision the same way the listeners do: by reading auth state
 * and querying the configured allowlist. We don't construct Velocity events directly since they
 * are not on the test classpath, but the data flow under test matches what the listener uses.
 */
class AllowlistRoutingTest {

    private AuthService authService(RuntimeContext context) {
        return new AuthService(
                new InMemoryAccountRepository(),
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
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

    @Test
    void unauthenticatedRoutesToLimboAuthenticatedToTarget() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        AuthService service = authService(context);
        UUID uuid = UUID.randomUUID();
        AuthState state = new AuthState(uuid, "Alice", "iphash", AuthState.Stage.AWAITING_LOGIN, AccountType.CRACKED);
        service.trackConnection(state);
        assertFalse(service.isAuthenticated(uuid), "Before login, route → limbo");
        state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);
        assertTrue(service.isAuthenticated(uuid), "After login, route → target");
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

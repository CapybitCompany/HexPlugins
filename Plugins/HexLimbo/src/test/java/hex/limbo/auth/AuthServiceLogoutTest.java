package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class AuthServiceLogoutTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();

    private AuthService authService() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        return new AuthService(
                new InMemoryAccountRepository(),
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
                connections,
                LoggerFactory.getLogger(AuthServiceLogoutTest.class)
        );
    }

    private ConnectionHandle track(AuthService service, String username, AuthState.Stage stage, AccountType type) {
        ConnectionHandle handle = FakeConnection.of(username).connect(connections);
        service.trackConnection(handle, new AuthState(handle.uuid(), username, "ip", stage, type));
        return handle;
    }

    @Test
    void crackedLogoutDemotesToAwaitingLogin() {
        AuthService service = authService();
        ConnectionHandle handle = track(service, "Alice", AuthState.Stage.AUTHENTICATED_CRACKED, AccountType.CRACKED);

        assertSame(AuthService.LogoutOutcome.SUCCESS, service.logout(handle));
        assertEquals(AuthState.Stage.AWAITING_LOGIN, handle.authState().orElseThrow().stage());
    }

    @Test
    void premiumLogoutDeniedAndStateUnchanged() {
        AuthService service = authService();
        ConnectionHandle handle = track(service, "Notch", AuthState.Stage.AUTHENTICATED_PREMIUM, AccountType.PREMIUM);

        assertSame(AuthService.LogoutOutcome.PREMIUM_NOT_SUPPORTED, service.logout(handle));
        AuthState state = handle.authState().orElseThrow();
        assertEquals(AuthState.Stage.AUTHENTICATED_PREMIUM, state.stage(),
                "Premium logout must NOT demote to AWAITING_LOGIN - the account has no password.");
        assertFalse(state.stage() == AuthState.Stage.AWAITING_LOGIN);
    }

    @Test
    void logoutOnUnknownConnectionReturnsNoState() {
        AuthService service = authService();
        assertSame(AuthService.LogoutOutcome.NO_STATE, service.logout(null));
    }

    @Test
    void logoutOnAnEndedConnectionReturnsNoState() {
        AuthService service = authService();
        ConnectionHandle handle = track(service, "Ivy", AuthState.Stage.AUTHENTICATED_CRACKED, AccountType.CRACKED);
        connections.end(handle);

        assertSame(AuthService.LogoutOutcome.NO_STATE, service.logout(handle),
                "a handle that is no longer current must not be able to change any state");
        assertEquals(AuthState.Stage.AUTHENTICATED_CRACKED, handle.authState().orElseThrow().stage());
    }
}

package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class AuthServiceLogoutTest {

    private AuthService authService() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        return new AuthService(
                new InMemoryAccountRepository(),
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
                LoggerFactory.getLogger(AuthServiceLogoutTest.class)
        );
    }

    @Test
    void crackedLogoutDemotesToAwaitingLogin() {
        AuthService service = authService();
        UUID uuid = UUID.randomUUID();
        AuthState state = new AuthState(uuid, "Alice", "ip", AuthState.Stage.AUTHENTICATED_CRACKED, AccountType.CRACKED);
        service.trackConnection(state);
        assertSame(AuthService.LogoutOutcome.SUCCESS, service.logout(uuid));
        assertEquals(AuthState.Stage.AWAITING_LOGIN, state.stage());
    }

    @Test
    void premiumLogoutDeniedAndStateUnchanged() {
        AuthService service = authService();
        UUID uuid = UUID.randomUUID();
        AuthState state = new AuthState(uuid, "Notch", "ip", AuthState.Stage.AUTHENTICATED_PREMIUM, AccountType.PREMIUM);
        service.trackConnection(state);
        assertSame(AuthService.LogoutOutcome.PREMIUM_NOT_SUPPORTED, service.logout(uuid));
        assertEquals(AuthState.Stage.AUTHENTICATED_PREMIUM, state.stage(),
                "Premium logout must NOT demote to AWAITING_LOGIN – the account has no password.");
        assertFalse(state.stage() == AuthState.Stage.AWAITING_LOGIN);
    }

    @Test
    void logoutOnUnknownUuidReturnsNoState() {
        AuthService service = authService();
        assertSame(AuthService.LogoutOutcome.NO_STATE, service.logout(UUID.randomUUID()));
    }
}

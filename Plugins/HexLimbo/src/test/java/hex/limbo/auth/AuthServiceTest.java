package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private InMemoryAccountRepository repository;
    private RuntimeContext runtimeContext;
    private AuthService authService;
    private PluginConfig config;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        config = TestConfigs.defaultConfig();
        runtimeContext = new RuntimeContext(config, new MessagesConfig(Map.of()));
        authService = new AuthService(
                repository,
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                runtimeContext,
                LoggerFactory.getLogger(AuthServiceTest.class)
        );
    }

    private AuthState track(String username) {
        UUID uuid = UUID.nameUUIDFromBytes(("u:" + username).getBytes());
        AuthState state = new AuthState(uuid, username, "iphash-test", AuthState.Stage.UNREGISTERED, AccountType.CRACKED);
        authService.trackConnection(state);
        return state;
    }

    @Test
    void registerSucceedsForCrackedName() {
        AuthState state = track("Alice");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state.uuid(), "verylongpw", "verylongpw", false);
        assertEquals(AuthService.RegisterOutcome.SUCCESS, outcome);
        assertTrue(authService.isAuthenticated(state.uuid()));
        assertNotNull(repository.findByUsername("alice").orElse(null));
    }

    @Test
    void registerBlockedForPremiumName() {
        AuthState state = track("Notch");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state.uuid(), "verylongpw", "verylongpw", true);
        assertEquals(AuthService.RegisterOutcome.PREMIUM_NAME_PROTECTED, outcome);
        assertFalse(authService.isAuthenticated(state.uuid()));
    }

    @Test
    void registerRejectsShortPassword() {
        AuthState state = track("Bob");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state.uuid(), "short", "short", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_TOO_SHORT, outcome);
    }

    @Test
    void registerRejectsMismatch() {
        AuthState state = track("Carl");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state.uuid(), "longenough1", "longenough2", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_MISMATCH, outcome);
    }

    @Test
    void loginSucceedsAfterRegister() {
        AuthState reg = track("Dave");
        authService.attemptRegister(reg.uuid(), "verylongpw", "verylongpw", false);
        authService.removeConnection(reg.uuid());

        AuthState state = track("Dave");
        AuthService.LoginOutcome outcome = authService.attemptLogin(state.uuid(), "verylongpw");
        assertEquals(AuthService.LoginOutcome.SUCCESS, outcome);
    }

    @Test
    void loginLocksAfterMaxFailedAttempts() {
        AuthState reg = track("Eve");
        authService.attemptRegister(reg.uuid(), "verylongpw", "verylongpw", false);
        authService.removeConnection(reg.uuid());

        AuthState state = track("Eve");
        for (int i = 0; i < config.security().maxFailedAttempts(); i++) {
            AuthService.LoginOutcome outcome = authService.attemptLogin(state.uuid(), "wrong-pw");
            assertEquals(AuthService.LoginOutcome.WRONG_PASSWORD, outcome);
        }
        AuthService.LoginOutcome locked = authService.attemptLogin(state.uuid(), "verylongpw");
        assertEquals(AuthService.LoginOutcome.ACCOUNT_LOCKED, locked);
    }

    @Test
    void maxAccountsPerIpEnforced() {
        for (int i = 0; i < config.security().maxAccountsPerIp(); i++) {
            AuthState s = track("User" + i);
            assertEquals(AuthService.RegisterOutcome.SUCCESS,
                    authService.attemptRegister(s.uuid(), "verylongpw", "verylongpw", false));
            authService.removeConnection(s.uuid());
        }
        AuthState overflow = track("Spammer");
        assertEquals(AuthService.RegisterOutcome.TOO_MANY_ACCOUNTS_FOR_IP,
                authService.attemptRegister(overflow.uuid(), "verylongpw", "verylongpw", false));
    }

    @Test
    void changePasswordRequiresOldPassword() {
        AuthState state = track("Frank");
        authService.attemptRegister(state.uuid(), "verylongpw", "verylongpw", false);
        assertFalse(authService.changePassword(state.uuid(), "wrong-pw", "newlongpw"));
        assertTrue(authService.changePassword(state.uuid(), "verylongpw", "newlongpw"));
    }

    @Test
    void reloadChangesActiveSecurityLimits() {
        AuthState state = track("Grace");
        // Tighten password length via reload; the new minimum must take effect immediately.
        PluginConfig stricter = TestConfigs.withMinPasswordLength(20);
        runtimeContext.update(stricter, new MessagesConfig(Map.of()));
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state.uuid(), "verylongpw", "verylongpw", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_TOO_SHORT, outcome);
    }
}

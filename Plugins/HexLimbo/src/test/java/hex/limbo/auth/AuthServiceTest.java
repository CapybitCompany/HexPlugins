package hex.limbo.auth;

import hex.limbo.account.AccountType;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private InMemoryAccountRepository repository;
    private RuntimeContext runtimeContext;
    private ConnectionRegistry connections;
    private AuthService authService;
    private PluginConfig config;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountRepository();
        config = TestConfigs.defaultConfig();
        runtimeContext = new RuntimeContext(config, new MessagesConfig(Map.of()));
        connections = new ConnectionRegistry();
        authService = new AuthService(
                repository,
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                runtimeContext,
                connections,
                LoggerFactory.getLogger(AuthServiceTest.class)
        );
    }

    /** Opens a connection and attaches a fresh unregistered auth state, like the login pipeline. */
    private ConnectionHandle track(String username) {
        ConnectionHandle handle = FakeConnection.of(username).connect(connections);
        authService.trackConnection(handle, new AuthState(
                handle.uuid(), username, "iphash-test", AuthState.Stage.UNREGISTERED, AccountType.CRACKED));
        return handle;
    }

    @Test
    void registerSucceedsForCrackedName() {
        ConnectionHandle state = track("Alice");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state, "verylongpw", "verylongpw", false);
        assertEquals(AuthService.RegisterOutcome.SUCCESS, outcome);
        assertTrue(authService.isAuthenticated(state.uuid()));
        assertNotNull(repository.findByUsername("alice").orElse(null));
    }

    @Test
    void registerBlockedForPremiumName() {
        ConnectionHandle state = track("Notch");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state, "verylongpw", "verylongpw", true);
        assertEquals(AuthService.RegisterOutcome.PREMIUM_NAME_PROTECTED, outcome);
        assertFalse(authService.isAuthenticated(state.uuid()));
    }

    @Test
    void registerRejectsShortPassword() {
        ConnectionHandle state = track("Bob");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state, "short", "short", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_TOO_SHORT, outcome);
    }

    @Test
    void registerRejectsMismatch() {
        ConnectionHandle state = track("Carl");
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state, "longenough1", "longenough2", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_MISMATCH, outcome);
    }

    @Test
    void loginSucceedsAfterRegister() {
        ConnectionHandle reg = track("Dave");
        authService.attemptRegister(reg, "verylongpw", "verylongpw", false);
        connections.end(reg);

        ConnectionHandle state = track("Dave");
        AuthService.LoginOutcome outcome = authService.attemptLogin(state, "verylongpw");
        assertEquals(AuthService.LoginOutcome.SUCCESS, outcome);
    }

    @Test
    void loginLocksAfterMaxFailedAttempts() {
        ConnectionHandle reg = track("Eve");
        authService.attemptRegister(reg, "verylongpw", "verylongpw", false);
        connections.end(reg);

        ConnectionHandle state = track("Eve");
        for (int i = 0; i < config.security().maxFailedAttempts(); i++) {
            AuthService.LoginOutcome outcome = authService.attemptLogin(state, "wrong-pw");
            assertEquals(AuthService.LoginOutcome.WRONG_PASSWORD, outcome);
        }
        AuthService.LoginOutcome locked = authService.attemptLogin(state, "verylongpw");
        assertEquals(AuthService.LoginOutcome.ACCOUNT_LOCKED, locked);
    }

    @Test
    void maxAccountsPerIpEnforced() {
        for (int i = 0; i < config.security().maxAccountsPerIp(); i++) {
            ConnectionHandle s = track("User" + i);
            assertEquals(AuthService.RegisterOutcome.SUCCESS,
                    authService.attemptRegister(s, "verylongpw", "verylongpw", false));
            connections.end(s);
        }
        ConnectionHandle overflow = track("Spammer");
        assertEquals(AuthService.RegisterOutcome.TOO_MANY_ACCOUNTS_FOR_IP,
                authService.attemptRegister(overflow, "verylongpw", "verylongpw", false));
    }

    @Test
    void changePasswordRequiresOldPassword() {
        ConnectionHandle state = track("Frank");
        authService.attemptRegister(state, "verylongpw", "verylongpw", false);
        assertFalse(authService.changePassword(state, "wrong-pw", "newlongpw"));
        assertTrue(authService.changePassword(state, "verylongpw", "newlongpw"));
    }

    @Test
    void reloadChangesActiveSecurityLimits() {
        ConnectionHandle state = track("Grace");
        // Tighten password length via reload; the new minimum must take effect immediately.
        PluginConfig stricter = TestConfigs.withMinPasswordLength(20);
        runtimeContext.update(stricter, new MessagesConfig(Map.of()));
        AuthService.RegisterOutcome outcome = authService.attemptRegister(state, "verylongpw", "verylongpw", false);
        assertEquals(AuthService.RegisterOutcome.PASSWORD_TOO_SHORT, outcome);
    }
}

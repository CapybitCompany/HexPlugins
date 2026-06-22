package hex.limbo.auth;

import hex.limbo.account.AccountType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the in-memory side of session auto-login: when LoginListener flips the stage to
 * AUTHENTICATED_CRACKED based on a session lookup, downstream queries must reflect that. Real DB
 * round-trips are exercised via the SQL repository which is integration-test territory.
 */
class SessionFlowTest {

    @Test
    void sessionStageFlipsToAuthenticated() {
        UUID uuid = UUID.randomUUID();
        AuthState state = new AuthState(uuid, "Alice", "iphash", AuthState.Stage.AWAITING_LOGIN, AccountType.CRACKED);
        assertFalse(state.isAuthenticated());

        // Auto-login simulation – the LoginListener flips the stage when SessionService returns a
        // valid expiry for the (uuid, ipHash) tuple.
        state.setStage(AuthState.Stage.AUTHENTICATED_CRACKED);

        assertTrue(state.isAuthenticated());
        assertEquals(AuthState.Stage.AUTHENTICATED_CRACKED, state.stage());
    }
}

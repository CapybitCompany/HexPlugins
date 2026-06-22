package hex.limbo.auth;

import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the database is unavailable we instantiate {@link SessionService} with a {@code null}
 * datasource. Every operation must no-op safely.
 */
class SessionServiceNullSafeTest {

    private final RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
    private final SessionService service = new SessionService(
            null, context, LoggerFactory.getLogger(SessionServiceNullSafeTest.class));

    @Test
    void disabledWhenDataSourceIsNull() {
        assertFalse(service.isEnabled());
    }

    @Test
    void createSessionIsNoOp() {
        service.createSession(1L, UUID.randomUUID(), "alice", "ip-hash");
    }

    @Test
    void findValidSessionExpiryReturnsEmpty() {
        assertTrue(service.findValidSessionExpiry(UUID.randomUUID(), "ip-hash").isEmpty());
    }

    @Test
    void findLatestExpiryForUuidReturnsEmpty() {
        assertTrue(service.findLatestExpiryForUuid(UUID.randomUUID()).isEmpty());
    }

    @Test
    void countValidSessionsForUuidIsZero() {
        assertEquals(0, service.countValidSessionsForUuid(UUID.randomUUID()));
    }

    @Test
    void invalidateIsNoOp() {
        service.invalidate(UUID.randomUUID());
    }

    @Test
    void purgeExpiredReturnsZero() {
        assertEquals(0, service.purgeExpired());
    }
}

package hex.limbo.db;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuditLogServiceNullSafeTest {

    @Test
    void disabledWhenDataSourceIsNull() {
        AuditLogService service = new AuditLogService(null, Runnable::run,
                LoggerFactory.getLogger(AuditLogServiceNullSafeTest.class));
        assertFalse(service.isEnabled());
    }

    @Test
    void recordDoesNotSchedule() {
        AtomicInteger counter = new AtomicInteger();
        Executor executor = task -> counter.incrementAndGet();
        AuditLogService service = new AuditLogService(null, executor,
                LoggerFactory.getLogger(AuditLogServiceNullSafeTest.class));
        service.record("LOGIN", "alice", UUID.randomUUID(), "ip-hash", null);
        assertEquals(0, counter.get(), "No work should be dispatched when no datasource is configured.");
    }

    @Test
    void disabledWhenExecutorIsNull() {
        AuditLogService service = new AuditLogService(null, null,
                LoggerFactory.getLogger(AuditLogServiceNullSafeTest.class));
        assertFalse(service.isEnabled());
        // Should not throw.
        service.record("LOGIN", "alice", UUID.randomUUID(), "ip-hash", null);
    }
}

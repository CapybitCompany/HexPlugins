package hex.limbo.auth;

import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After {@code /hexlimbo reload}, {@link SessionService} must read the new {@code session.enabled}
 * flag and the new {@code session.duration-minutes} value on the next call – without being
 * re-instantiated. The presence of a (nullable) datasource is fixed at construction time.
 */
class SessionServiceConfigDrivenTest {

    private PluginConfig withSession(boolean enabled, long durationMinutes) {
        PluginConfig base = TestConfigs.defaultConfig();
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                List.copyOf(base.allowedCommandsUnauthenticated()),
                base.database(),
                new PluginConfig.Session(enabled, durationMinutes, base.session().purgeIntervalMinutes()),
                base.security(),
                base.premium(),
                base.limbo(),
                base.prompts()
        );
    }

    @Test
    void disabledWhenDataSourceIsNull() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        SessionService service = new SessionService(null, context, LoggerFactory.getLogger(SessionServiceConfigDrivenTest.class));
        assertFalse(service.isEnabled());
    }

    @Test
    void durationMillisReflectsRuntimeConfig() {
        RuntimeContext context = new RuntimeContext(withSession(true, 60L), new MessagesConfig(Map.of()));
        SessionService service = new SessionService(null, context, LoggerFactory.getLogger(SessionServiceConfigDrivenTest.class));
        assertEquals(60L * 60_000L, service.currentDurationMillis());
    }

    @Test
    void durationChangesAfterReload() {
        RuntimeContext context = new RuntimeContext(withSession(true, 30L), new MessagesConfig(Map.of()));
        SessionService service = new SessionService(null, context, LoggerFactory.getLogger(SessionServiceConfigDrivenTest.class));
        assertEquals(30L * 60_000L, service.currentDurationMillis());

        context.update(withSession(true, 720L), new MessagesConfig(Map.of()));
        assertEquals(720L * 60_000L, service.currentDurationMillis(),
                "After reload, new createSession calls must use the updated duration.");
    }

    @Test
    void durationFloorIsOneMinute() {
        // Even if the config holds something silly like 0 minutes, the service guarantees at least
        // one minute so we never write a session that's already expired.
        RuntimeContext context = new RuntimeContext(withSession(true, 0L), new MessagesConfig(Map.of()));
        SessionService service = new SessionService(null, context, LoggerFactory.getLogger(SessionServiceConfigDrivenTest.class));
        assertEquals(60_000L, service.currentDurationMillis());
    }

    @Test
    void enabledFlagTracksContext() {
        RuntimeContext context = new RuntimeContext(withSession(true, 240L), new MessagesConfig(Map.of()));
        // Without a real datasource we can still inspect the live config; the service's own
        // isEnabled() ANDs that with (dataSource != null), so we verify the context side here.
        assertTrue(context.config().session().enabled());
        context.update(withSession(false, 240L), new MessagesConfig(Map.of()));
        assertFalse(context.config().session().enabled());
    }
}

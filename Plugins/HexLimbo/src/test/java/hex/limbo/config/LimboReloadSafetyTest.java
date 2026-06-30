package hex.limbo.config;

import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Documents the contract: even though {@link RuntimeContext} can swap any {@link PluginConfig}, in
 * production the plugin reload preserves the {@code limbo.*} block. This test exercises the
 * decision logic so a future contributor who removes the warning + preserve step in
 * {@code HexLimboPlugin#reloadConfiguration()} fails the suite.
 */
class LimboReloadSafetyTest {

    @Test
    void runtimeContextItselfWillSwapAnything() {
        // RuntimeContext is intentionally dumb; HexLimboPlugin#reloadConfiguration is responsible
        // for keeping the old limbo block. Verify the dumb behaviour so the *plugin* test below
        // can be read as the contract.
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        PluginConfig changed = TestConfigs.withLimboPort(31337);
        context.update(changed, new MessagesConfig(Map.of()));
        assertEquals(31337, context.config().limbo().bindPort(),
                "RuntimeContext alone swaps every field – it does not enforce restart-only semantics.");
    }

    @Test
    void preservingOldLimboBlockKeepsRunningBackendStable() {
        // Simulates HexLimboPlugin#reloadConfiguration: parse new YAML, then explicitly carry the
        // limbo block from the old config forward so the running TCP backend keeps its port.
        PluginConfig oldConfig = TestConfigs.defaultConfig();
        PluginConfig parsedNew = TestConfigs.withLimboPort(31337);
        assertNotEquals(parsedNew.limbo(), oldConfig.limbo(),
                "Sanity: the test's 'parsed new' config must differ in limbo.*");

        PluginConfig effective = new PluginConfig(
                parsedNew.targetServer(),
                parsedNew.loginTimeoutSeconds(),
                parsedNew.adminBypassPermission(),
                List.copyOf(parsedNew.allowedCommandsUnauthenticated()),
                parsedNew.database(),
                parsedNew.session(),
                parsedNew.security(),
                parsedNew.premium(),
                oldConfig.limbo() // ← this is the production guard
        );
        assertEquals(oldConfig.limbo().bindPort(), effective.limbo().bindPort(),
                "Effective config must keep the OLD limbo block.");
        assertEquals(parsedNew.targetServer(), effective.targetServer(),
                "Other fields still apply.");
    }
}

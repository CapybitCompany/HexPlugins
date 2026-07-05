package hex.limbo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code limbo.*} block is loaded with sensible defaults and reflects custom values.
 */
class ConfigLoaderLimboTest {

    @Test
    void bundledDefaultsLoadLimboBlock(@TempDir Path tempDir) throws IOException {
        ConfigLoader loader = new ConfigLoader(tempDir, LoggerFactory.getLogger(ConfigLoaderLimboTest.class));
        PluginConfig config = loader.loadConfig();
        PluginConfig.Limbo limbo = config.limbo();
        assertEquals("hexlimbo-limbo", limbo.serverName());
        assertEquals("127.0.0.1", limbo.bindHost());
        assertEquals(25580, limbo.bindPort());
        assertEquals(0.5, limbo.spawnX(), 0.0001);
        assertEquals(64.0, limbo.spawnY(), 0.0001);
        assertEquals(0.5, limbo.spawnZ(), 0.0001);
        // v1 ships with actionbar OFF: the NBT text-component encoding is fragile across
        // client builds and a malformed packet kicks the player.
        assertEquals(false, limbo.actionbarEnabled());
        assertEquals("Zaloguj się przez /login lub zarejestruj przez /register.", limbo.actionbarText());
        assertEquals(false, limbo.debugProtocol());
    }

    @Test
    void customLimboBlockOverridesDefaults(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        java.nio.file.Files.writeString(configFile, """
                servers:
                  target: "main"
                limbo:
                  server-name: "void"
                  bind-host: "0.0.0.0"
                  bind-port: 30001
                  spawn:
                    x: 1.0
                    y: 100.0
                    z: 2.0
                    yaw: 90.0
                    pitch: 5.0
                  actionbar-enabled: false
                  actionbar-text: "Login first."
                """);
        ConfigLoader loader = new ConfigLoader(tempDir, LoggerFactory.getLogger(ConfigLoaderLimboTest.class));
        PluginConfig config = loader.loadConfig();
        PluginConfig.Limbo limbo = config.limbo();
        assertEquals("void", limbo.serverName());
        assertEquals("0.0.0.0", limbo.bindHost());
        assertEquals(30001, limbo.bindPort());
        assertEquals(1.0, limbo.spawnX(), 0.0001);
        assertEquals(100.0, limbo.spawnY(), 0.0001);
        assertEquals(2.0, limbo.spawnZ(), 0.0001);
        assertEquals(90.0f, limbo.spawnYaw(), 0.0001f);
        assertEquals(5.0f, limbo.spawnPitch(), 0.0001f);
        assertEquals(false, limbo.actionbarEnabled());
        assertEquals("Login first.", limbo.actionbarText());
        // Convenience accessor on PluginConfig still returns the limbo server name.
        assertEquals("void", config.limboServer());
    }
}

package hexnpc.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-Parsing fuer den neuen Sitz-Offset und die MineSkin-Einstellungen inkl.
 * Defaults (rueckwaerts-kompatibel: fehlende Schluessel liefern sinnvolle Standards).
 */
class HexNpcConfigLoaderTest {

    private final HexNpcConfigLoader loader = new HexNpcConfigLoader();

    @Test
    void defaultsWhenKeysMissing() {
        HexNpcConfig config = loader.load(new YamlConfiguration());
        assertEquals(HexNpcConfig.Render.DEFAULT_SITTING_Y_OFFSET, config.render().sittingYOffset(), 1e-9);
        assertEquals(-1.0D, config.render().sittingYOffset(), 1e-9);

        HexNpcConfig.Skins.MineSkin ms = config.skins().mineskin();
        assertFalse(ms.enabled(), "MineSkin standardmaessig aus");
        assertFalse(ms.hasApiKey());
        assertEquals("https://api.mineskin.org", ms.baseUrl());
        assertEquals("HexNPC/1.0", ms.userAgent());
    }

    @Test
    void readsSittingOffsetOverride() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("render.sitting-y-offset", -2.5D);
        HexNpcConfig config = loader.load(yaml);
        assertEquals(-2.5D, config.render().sittingYOffset(), 1e-9);
    }

    @Test
    void readsMineSkinSection() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("skins.mineskin.enabled", true);
        yaml.set("skins.mineskin.api-key", "secret-key");
        yaml.set("skins.mineskin.user-agent", "MyServer/9");
        yaml.set("skins.mineskin.base-url", "https://mirror.example/");
        yaml.set("skins.mineskin.request-timeout-seconds", 30);
        yaml.set("skins.mineskin.max-poll-attempts", 15);
        yaml.set("skins.mineskin.poll-interval-millis", 1500L);

        HexNpcConfig.Skins.MineSkin ms = loader.load(yaml).skins().mineskin();
        assertTrue(ms.enabled());
        assertTrue(ms.hasApiKey());
        assertEquals("secret-key", ms.apiKey());
        assertEquals("MyServer/9", ms.userAgent());
        assertEquals("https://mirror.example", ms.baseUrl(), "trailing slash wird entfernt");
        assertEquals(30, ms.requestTimeoutSeconds());
        assertEquals(15, ms.maxPollAttempts());
        assertEquals(1500L, ms.pollIntervalMillis());
    }
}

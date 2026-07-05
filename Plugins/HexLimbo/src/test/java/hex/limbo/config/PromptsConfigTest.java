package hex.limbo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bundled {@code prompts.*} config defaults load, and that the bundled
 * {@code messages.yml} prompt strings survive as UTF-8 Polish (correct diacritics).
 */
class PromptsConfigTest {

    private ConfigLoader loader(Path dir) {
        return new ConfigLoader(dir, LoggerFactory.getLogger(PromptsConfigTest.class));
    }

    @Test
    void bundledDefaultsLoadPromptsBlock(@TempDir Path tempDir) throws IOException {
        PluginConfig.Prompts prompts = loader(tempDir).loadConfig().prompts();
        assertTrue(prompts.enabled());
        assertTrue(prompts.bossbarEnabled());
        assertTrue(prompts.titleEnabled());
        assertTrue(prompts.chatEnabled());
        assertEquals(15L, prompts.reminderIntervalSeconds());
        assertEquals("RED", prompts.bossbarColor());
        assertEquals("PROGRESS", prompts.bossbarOverlay());
        assertEquals(1.0f, prompts.bossbarProgress(), 0.0001f);
        assertTrue(prompts.successTitleEnabled());
        assertTrue(prompts.premiumSkipEnabled());
    }

    @Test
    void customPromptsBlockOverridesDefaults(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        java.nio.file.Files.writeString(configFile, """
                prompts:
                  enabled: false
                  bossbar-enabled: false
                  reminder-interval-seconds: 30
                  bossbar-color: "BLUE"
                  bossbar-overlay: "NOTCHED_10"
                  bossbar-progress: 0.5
                  premium-skip-enabled: false
                """);
        PluginConfig.Prompts prompts = loader(tempDir).loadConfig().prompts();
        assertEquals(false, prompts.enabled());
        assertEquals(false, prompts.bossbarEnabled());
        assertEquals(30L, prompts.reminderIntervalSeconds());
        assertEquals("BLUE", prompts.bossbarColor());
        assertEquals("NOTCHED_10", prompts.bossbarOverlay());
        assertEquals(0.5f, prompts.bossbarProgress(), 0.0001f);
        assertEquals(false, prompts.premiumSkipEnabled());
    }

    @Test
    void bundledMessagesArePolishUtf8(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();
        assertEquals("Zaloguj się: /login <hasło>", messages.raw("prompts.login.bossbar"));
        assertEquals("Zaloguj się", messages.raw("prompts.login.title"));
        assertEquals("Wpisz /login <hasło>, aby wejść na serwer.", messages.raw("prompts.login.subtitle"));
        assertEquals("Musisz się zalogować. Użyj: /login <hasło>", messages.raw("prompts.login.chat"));
        assertEquals("Zarejestruj się: /register <hasło> <hasło>", messages.raw("prompts.register.bossbar"));
        assertEquals("Nie masz jeszcze konta. Użyj: /register <hasło> <hasło>", messages.raw("prompts.register.chat"));
        assertEquals("Zalogowano pomyślnie. Miłej gry na HexagonMC!", messages.raw("prompts.success.chat"));
        // Sanity: the diacritics really are present (guards against a mojibake regression).
        assertTrue(messages.raw("prompts.login.bossbar").contains("ę"));
        assertTrue(messages.raw("prompts.register.chat").contains("ł"));
    }
}

package hex.limbo.config;

import hex.limbo.prompt.AuthReason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bundled {@code prompts.*} config defaults load, that the bundled
 * {@code messages.yml} prompt strings survive as UTF-8 Polish (correct diacritics), and that they
 * render into properly coloured Adventure components.
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
        assertEquals("YELLOW", prompts.bossbarColor());
        assertEquals("PROGRESS", prompts.bossbarOverlay());
        assertEquals(1.0f, prompts.bossbarProgress(), 0.0001f);
        assertTrue(prompts.successTitleEnabled());
        assertTrue(prompts.premiumSuccessEnabled());
        assertTrue(prompts.adminBypassSuccessEnabled());
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
                  premium-success-enabled: false
                  admin-bypass-success-enabled: false
                """);
        PluginConfig.Prompts prompts = loader(tempDir).loadConfig().prompts();
        assertEquals(false, prompts.enabled());
        assertEquals(false, prompts.bossbarEnabled());
        assertEquals(30L, prompts.reminderIntervalSeconds());
        assertEquals("BLUE", prompts.bossbarColor());
        assertEquals("NOTCHED_10", prompts.bossbarOverlay());
        assertEquals(0.5f, prompts.bossbarProgress(), 0.0001f);
        assertEquals(false, prompts.premiumSuccessEnabled());
        assertEquals(false, prompts.adminBypassSuccessEnabled());
    }

    /**
     * A hand-written config that omits the styling keys must fall back to the same values the
     * bundled file ships, not to the pre-2.0 red BossBar.
     */
    @Test
    void minimalConfigWithoutBossbarColourFallsBackToYellow(@TempDir Path tempDir) throws IOException {
        java.nio.file.Files.writeString(tempDir.resolve("config.yml"), """
                config-version: 2
                prompts:
                  enabled: true
                """);

        PluginConfig.Prompts prompts = loader(tempDir).loadConfig().prompts();

        assertEquals("YELLOW", prompts.bossbarColor(), "the code fallback must match the bundled default");
        assertEquals("PROGRESS", prompts.bossbarOverlay());
        assertEquals(1.0f, prompts.bossbarProgress(), 0.0001f);
    }

    /** An entirely empty config.yml must still yield the documented defaults. */
    @Test
    void emptyConfigYieldsTheBundledPromptDefaults(@TempDir Path tempDir) throws IOException {
        java.nio.file.Files.writeString(tempDir.resolve("config.yml"), "config-version: 2\n");

        PluginConfig.Prompts prompts = loader(tempDir).loadConfig().prompts();

        assertEquals("YELLOW", prompts.bossbarColor());
        assertTrue(prompts.enabled());
        assertTrue(prompts.successTitleEnabled());
        assertTrue(prompts.premiumSuccessEnabled());
        assertTrue(prompts.adminBypassSuccessEnabled());
    }

    /** An unparseable colour must not fall through to the old red default either. */
    @Test
    void invalidBossbarColourFallsBackToYellowAtRenderTime() {
        PluginConfig.Prompts prompts = new PluginConfig.Prompts(
                true, true, true, true, 15L, "NOT_A_COLOUR", "PROGRESS", 1.0f, true, true, true);
        RuntimeContext context = new RuntimeContext(
                hex.limbo.testsupport.TestConfigs.withPrompts(prompts),
                new MessagesConfig(java.util.Map.of("prompts.login.bossbar", "&6Hex")));
        hex.limbo.auth.ConnectionRegistry connections = new hex.limbo.auth.ConnectionRegistry();
        hex.limbo.prompt.PromptService service = new hex.limbo.prompt.PromptService(
                context, connections, (interval, task) -> () -> { });
        hex.limbo.testsupport.FakeConnection player = hex.limbo.testsupport.FakeConnection.of("Colour");

        service.showLimboPrompt(player.connect(connections), hex.limbo.auth.AuthState.Stage.AWAITING_LOGIN);

        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.YELLOW, player.shownBars.get(0).color());
    }

    @Test
    void bundledMessagesArePolishUtf8(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();
        assertEquals("&6Hex &8\u00bb &7Zaloguj si\u0119: &f/login <has\u0142o>", messages.raw("prompts.login.bossbar"));
        assertEquals("&6&lHEX", messages.raw("prompts.login.title"));
        assertEquals("&7Zaloguj si\u0119: &f/login <has\u0142o>", messages.raw("prompts.login.subtitle"));
        assertEquals("&8\u00bb &7Musisz si\u0119 zalogowa\u0107. U\u017cyj: &f/login <has\u0142o>", messages.raw("prompts.login.chat"));
        assertEquals("&6Hex &8\u00bb &7Zarejestruj si\u0119: &f/register <has\u0142o> <has\u0142o>", messages.raw("prompts.register.bossbar"));
        assertEquals("&8\u00bb &7Nie masz jeszcze konta. U\u017cyj: &f/register <has\u0142o> <has\u0142o>", messages.raw("prompts.register.chat"));
        assertEquals("&8\u00bb &aZalogowano pomy\u015blnie. &7Mi\u0142ej gry na &6Hex&7!", messages.raw("prompts.success.chat"));
    }

    /**
     * The whole Polish alphabet has to survive the resource copy; a broken encoding turns these
     * into mojibake long before anyone notices in game.
     */
    @Test
    void bundledMessagesKeepEveryPolishDiacritic(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();
        String all = String.join("\n", messages.asMap().values());
        for (String letter : new String[] {"\u0105", "\u0107", "\u0119", "\u0142", "\u0144", "\u00f3", "\u015b", "\u017a", "\u017c"}) {
            assertTrue(all.contains(letter), "messages.yml lost the Polish letter " + letter);
        }
        assertFalse(all.contains("\ufffd"), "messages.yml contains a replacement character (broken encoding)");
    }

    /** Every message key that HexLimbo shows to a player must be present in the bundled file. */
    @Test
    void bundledMessagesDefineEveryGreetingKey(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();
        for (AuthReason reason : AuthReason.values()) {
            for (String key : new String[] {reason.chatKey(), reason.titleKey(), reason.subtitleKey()}) {
                assertNotEquals(key, messages.raw(key), "messages.yml is missing " + key);
            }
        }
    }

    /**
     * The success titles must render as real Adventure styling, with the literal {@code <has\u0142o>}
     * placeholder intact and no leftover ampersands.
     */
    @Test
    void bundledMessagesRenderAsColouredComponents(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();

        Component subtitle = messages.component("prompts.login.subtitle");
        assertEquals("Zaloguj si\u0119: /login <has\u0142o>", PlainTextComponentSerializer.plainText().serialize(subtitle));
        assertEquals(2, subtitle.children().size(), "the subtitle must render as two coloured runs");
        assertEquals(NamedTextColor.GRAY, subtitle.children().get(0).color(), "explanation is grey");
        assertEquals(NamedTextColor.WHITE, subtitle.children().get(1).color(), "the command is white");

        Component title = messages.component("prompts.success.title");
        assertEquals(NamedTextColor.GREEN, title.color());
        assertEquals(TextDecoration.State.TRUE, title.decoration(TextDecoration.BOLD));
        assertEquals("Zalogowano pomy\u015blnie!", PlainTextComponentSerializer.plainText().serialize(title));
    }

    /**
     * disconnect.forwarding-failed is written straight into a protocol packet by the internal
     * backend, where "&" codes are not translated. It must therefore stay colour-free.
     */
    @Test
    void protocolLevelDisconnectMessageStaysUncoloured(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();
        assertFalse(messages.raw("disconnect.forwarding-failed").contains("&"));
    }
}

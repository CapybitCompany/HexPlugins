package hexchat.config;

import hexchat.support.CapturingLogger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HexChatConfigLoaderTest {

    private static HexChatConfig load(String yaml, CapturingLogger log) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (InvalidConfigurationException ex) {
            throw new AssertionError("Niepoprawny YAML w teście", ex);
        }

        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getLogger()).thenReturn(log.logger());

        return new HexChatConfigLoader(plugin).load();
    }

    @Test
    void loadsDefaultsWhenValuesMissingOrEmpty() {
        CapturingLogger log = new CapturingLogger();
        // Pusty config -> wszystkie wartości powinny spaść na domyślne.
        HexChatConfig config = load("chat:\n  enabled: true\n", log);

        assertTrue(config.chat().enabled());
        assertEquals(
                "<gray>[<gold>Chat</gold>]</gray> <player><dark_gray>:</dark_gray> <message>",
                config.chat().format()
        );
        assertEquals("hexchat.chatmute.bypass", config.chat().globalMute().bypassPermission());
        assertEquals(10, config.cooldown().defaultSeconds());
        assertEquals("hexchat.cooldown.bypass", config.cooldown().bypassPermission());
        // Domyślna mapa rang cooldownu (6 pozycji).
        assertEquals(6, config.cooldown().rankCooldowns().size());
        assertEquals(HexChatConfig.Help.Mode.CUSTOM, config.help().mode());
        assertFalse(config.commandFilter().allowedCommands().isEmpty());
    }

    @Test
    void invalidHelpModeFallsBackToCustom() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = load("help:\n  mode: \"NIE_ISTNIEJE\"\n", log);

        assertEquals(HexChatConfig.Help.Mode.CUSTOM, config.help().mode());
        assertTrue(log.hasWarningContaining("help.mode"));
    }

    @Test
    void helpModeIsCaseInsensitive() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = load("help:\n  mode: \"essentials\"\n", log);

        assertEquals(HexChatConfig.Help.Mode.ESSENTIALS, config.help().mode());
    }

    @Test
    void legacyCooldownBypassIsReadWhenModernKeyMissing() {
        CapturingLogger log = new CapturingLogger();
        String yaml = "chat:\n  cooldown:\n    bypass: \"legacy.bypass.perm\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals("legacy.bypass.perm", config.cooldown().bypassPermission());
        assertTrue(log.hasWarningContaining("legacy"));
    }

    @Test
    void modernCooldownBypassTakesPrecedenceOverLegacy() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "cooldown:\n"
                + "  bypass-permission: \"modern.perm\"\n"
                + "chat:\n"
                + "  cooldown:\n"
                + "    bypass: \"legacy.perm\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals("modern.perm", config.cooldown().bypassPermission());
    }

    @Test
    void negativeCooldownIsClampedToZero() {
        CapturingLogger log = new CapturingLogger();
        String yaml = "cooldown:\n  default-seconds: -50\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(0, config.cooldown().defaultSeconds());
    }

    @Test
    void negativeRankCooldownIsClampedToZero() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "cooldown:\n"
                + "  rank-cooldowns:\n"
                + "    - rank: \"VIP\"\n"
                + "      seconds: -5\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(1, config.cooldown().rankCooldowns().size());
        assertEquals(0, config.cooldown().rankCooldowns().get(0).seconds());
    }

    @Test
    void emptyListsFallBackToDefaults() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "command-filter:\n"
                + "  allowed-commands: []\n"
                + "tab-complete-filter:\n"
                + "  hidden-commands: []\n"
                + "auto-messages:\n"
                + "  messages: []\n";
        HexChatConfig config = load(yaml, log);

        assertFalse(config.commandFilter().allowedCommands().isEmpty());
        assertFalse(config.tabCompleteFilter().hiddenCommands().isEmpty());
        assertFalse(config.autoMessages().messages().isEmpty());
    }

    @Test
    void blankListEntriesFallBackToDefaults() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "auto-messages:\n"
                + "  messages:\n"
                + "    - \"   \"\n"
                + "    - \"\"\n";
        HexChatConfig config = load(yaml, log);

        assertFalse(config.autoMessages().messages().isEmpty());
        assertTrue(log.hasWarningContaining("auto-messages.messages"));
    }

    @Test
    void modernAllowedCommandsDoNotProduceSpuriousWarnings() {
        CapturingLogger log = new CapturingLogger();
        // Nowoczesny config z poprawną listą allowed-commands (klucz z legacy fallbackiem)
        // nie powinien generować ŻADNEGO ostrzeżenia.
        String yaml = ""
                + "command-filter:\n"
                + "  allowed-commands:\n"
                + "    - \"help\"\n"
                + "    - \"spawn\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(2, config.commandFilter().allowedCommands().size());
        assertFalse(
                log.hasWarningContaining("allowed-commands"),
                "Nowoczesny config nie powinien generować ostrzeżeń o allowed-commands"
        );
        assertFalse(
                log.hasWarningContaining("legacy"),
                "Nowoczesny config nie powinien generować ostrzeżeń o legacy"
        );
    }

    @Test
    void allowedCommandsUseLegacyBlockedCommandsKeyWhenModernMissing() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "command-filter:\n"
                + "  blocked-commands:\n"
                + "    - \"stara-komenda\"\n";
        HexChatConfig config = load(yaml, log);

        assertTrue(config.commandFilter().allowedCommands().contains("stara-komenda"));
        assertTrue(log.hasWarningContaining("legacy"));
    }
}

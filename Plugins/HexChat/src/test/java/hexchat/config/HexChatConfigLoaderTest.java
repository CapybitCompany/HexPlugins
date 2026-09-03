package hexchat.config;

import hexchat.support.CapturingLogger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HexChatConfigLoaderTest {

    private static YamlConfiguration parse(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (InvalidConfigurationException ex) {
            throw new AssertionError("Niepoprawny YAML w teście", ex);
        }
        return configuration;
    }

    private static HexChatConfig load(String yaml, CapturingLogger log) {
        return load(parse(yaml), log);
    }

    private static HexChatConfig load(YamlConfiguration configuration, CapturingLogger log) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(configuration);
        when(plugin.getLogger()).thenReturn(log.logger());

        return new HexChatConfigLoader(plugin).load();
    }

    /**
     * Wbudowany config.yml pluginu — dokładnie to, co Bukkit podstawia jako wartości domyślne
     * obok zapisanej konfiguracji użytkownika.
     */
    private static YamlConfiguration embeddedDefaults() {
        try (InputStream stream = HexChatConfigLoaderTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(stream, "Brak wbudowanego config.yml na classpath testów");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new AssertionError("Nie udało się wczytać wbudowanego config.yml", ex);
        }
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
    void loadsContentFilterPlayerMuteAndConflictGuardDefaults() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = load("chat:\n  enabled: true\n", log);

        assertTrue(config.contentFilter().enabled());
        assertEquals("hexchat.filter.bypass", config.contentFilter().bypassPermission());
        assertEquals("***", config.contentFilter().censorMask());
        assertEquals(HexChatConfig.FilterAction.BLOCK, config.contentFilter().antiAdvertising().action());
        assertTrue(config.playerMute().enabled());
        assertEquals("hexchat.mute.bypass", config.playerMute().bypassPermission());
        assertTrue(config.chat().conflictGuard().enabled());
        assertFalse(config.chat().conflictGuard().enforceFormat());
        assertFalse(config.chat().conflictGuard().knownChatPlugins().isEmpty());
    }

    @Test
    void invalidFilterActionFallsBackToBlock() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "content-filter:\n"
                + "  blacklist:\n"
                + "    action: \"NIEZNANE\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(HexChatConfig.FilterAction.BLOCK, config.contentFilter().blacklist().action());
        assertTrue(log.hasWarningContaining("action"));
    }

    @Test
    void parsesCensorActionCaseInsensitive() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "content-filter:\n"
                + "  anti-advertising:\n"
                + "    action: \"censor\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(HexChatConfig.FilterAction.CENSOR, config.contentFilter().antiAdvertising().action());
    }

    @Test
    void antiSpamValuesAreClampedToSaneRanges() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "content-filter:\n"
                + "  anti-spam:\n"
                + "    max-repeated-messages: 1\n"
                + "    max-caps-percentage: 500\n"
                + "    min-length-for-caps-check: -3\n";
        HexChatConfig config = load(yaml, log);

        assertEquals(2, config.contentFilter().antiSpam().maxRepeatedMessages());
        assertEquals(100, config.contentFilter().antiSpam().maxCapsPercentage());
        assertEquals(1, config.contentFilter().antiSpam().minLengthForCapsCheck());
    }

    @Test
    void blacklistHardeningOptionsDefaultToEnabled() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = load("chat:\n  enabled: true\n", log);

        assertTrue(config.contentFilter().blacklist().matchLeetspeak());
        assertTrue(config.contentFilter().blacklist().ignoreSeparators());
        assertTrue(config.contentFilter().blacklist().matchWordEndings());
    }

    @Test
    void embeddedConfigShipsBlacklistHardeningKeys() {
        CapturingLogger log = new CapturingLogger();
        YamlConfiguration embedded = embeddedDefaults();

        // Klucze muszą być realnie zapisane we wbudowanym config.yml, a nie tylko opisane
        // w komentarzu — inaczej administrator nie ma czego przestawić.
        assertTrue(
                embedded.contains("content-filter.blacklist.match-leetspeak", true),
                "Wbudowany config.yml musi zawierać klucz match-leetspeak"
        );
        assertTrue(
                embedded.contains("content-filter.blacklist.ignore-separators", true),
                "Wbudowany config.yml musi zawierać klucz ignore-separators"
        );
        assertTrue(
                embedded.contains("content-filter.blacklist.match-word-endings", true),
                "Wbudowany config.yml musi zawierać klucz match-word-endings"
        );

        HexChatConfig config = load(embedded, log);
        assertTrue(config.contentFilter().blacklist().matchLeetspeak());
        assertTrue(config.contentFilter().blacklist().ignoreSeparators());
        assertTrue(config.contentFilter().blacklist().matchWordEndings());
    }

    @Test
    void blacklistHardeningOptionsCanBeDisabled() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "content-filter:\n"
                + "  blacklist:\n"
                + "    match-leetspeak: false\n"
                + "    ignore-separators: false\n"
                + "    match-word-endings: false\n";
        HexChatConfig config = load(yaml, log);

        assertFalse(config.contentFilter().blacklist().matchLeetspeak());
        assertFalse(config.contentFilter().blacklist().ignoreSeparators());
        assertFalse(config.contentFilter().blacklist().matchWordEndings());
    }

    @Test
    void missingMuteMessageKeysStayBackwardsCompatible() {
        CapturingLogger log = new CapturingLogger();
        // Starszy config: brak 'player-mute-notification' i 'mute-time-permanent'.
        String yaml = ""
                + "messages:\n"
                + "  private-muted: \"<red>Stary tekst <time></red>\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals("<red>Stary tekst <time></red>", config.messages().privateMuted());
        assertEquals("<red>Stary tekst <time></red>", config.messages().playerMuteNotification());
        assertEquals("na zawsze", config.messages().muteTimePermanent());
        assertFalse(
                log.hasWarningContaining("player-mute-notification"),
                "Brak nowego klucza w starszym configu nie jest błędem"
        );
        assertFalse(
                log.hasWarningContaining("mute-time-permanent"),
                "Brak nowego klucza w starszym configu nie jest błędem"
        );
    }

    @Test
    void newMuteKeysIgnoreBukkitDefaultsAndFallBackToUserPrivateMuted() {
        CapturingLogger log = new CapturingLogger();
        // Config użytkownika bez nowych kluczy...
        YamlConfiguration user = parse("messages:\n  private-muted: \"<red>STARY <time></red>\"\n");
        // ...plus wartości domyślne dostarczone przez Bukkit z wbudowanego config.yml.
        user.setDefaults(parse(""
                + "messages:\n"
                + "  private-muted: \"<red>wbudowany private-muted</red>\"\n"
                + "  player-mute-notification: \"<red>WBUDOWANE <player></red>\"\n"
                + "  mute-time-permanent: \"WBUDOWANE ZAWSZE\"\n"));

        HexChatConfig config = load(user, log);

        assertEquals("<red>STARY <time></red>", config.messages().privateMuted());
        assertEquals(
                "<red>STARY <time></red>",
                config.messages().playerMuteNotification(),
                "Brakujący klucz musi przejąć wartość z private-muted, a nie wbudowany default"
        );
        assertEquals("na zawsze", config.messages().muteTimePermanent());
    }

    @Test
    void newMuteKeysFallBackAlsoWithRealEmbeddedConfigAsDefaults() {
        CapturingLogger log = new CapturingLogger();
        YamlConfiguration defaults = embeddedDefaults();
        assertTrue(
                defaults.contains("messages.player-mute-notification"),
                "Wbudowany config.yml powinien zawierać nowy klucz"
        );

        YamlConfiguration user = parse("messages:\n  private-muted: \"<red>STARY <time></red>\"\n");
        user.setDefaults(defaults);

        HexChatConfig config = load(user, log);

        assertEquals("<red>STARY <time></red>", config.messages().playerMuteNotification());
        assertEquals("na zawsze", config.messages().muteTimePermanent());
    }

    @Test
    void explicitNewMuteKeysWinOverFallback() {
        CapturingLogger log = new CapturingLogger();
        YamlConfiguration user = parse(""
                + "messages:\n"
                + "  private-muted: \"<red>STARY <time></red>\"\n"
                + "  player-mute-notification: \"<red>WŁASNE <player></red>\"\n"
                + "  mute-time-permanent: \"na wieki wieków\"\n");
        user.setDefaults(embeddedDefaults());

        HexChatConfig config = load(user, log);

        assertEquals("<red>WŁASNE <player></red>", config.messages().playerMuteNotification());
        assertEquals("na wieki wieków", config.messages().muteTimePermanent());
    }

    @Test
    void muteMessageKeysAreReadWhenPresent() {
        CapturingLogger log = new CapturingLogger();
        String yaml = ""
                + "messages:\n"
                + "  private-muted: \"<red>Piszesz <time></red>\"\n"
                + "  player-mute-notification: \"<red>Wyciszony <player> <time> <reason></red>\"\n"
                + "  mute-time-permanent: \"na wieki wieków\"\n";
        HexChatConfig config = load(yaml, log);

        assertEquals("<red>Piszesz <time></red>", config.messages().privateMuted());
        assertEquals("<red>Wyciszony <player> <time> <reason></red>", config.messages().playerMuteNotification());
        assertEquals("na wieki wieków", config.messages().muteTimePermanent());
    }

    @Test
    void defaultMuteMessagesAreUsedWhenMessagesSectionMissing() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = load("chat:\n  enabled: true\n", log);

        assertEquals("<red>Jesteś wyciszony (<time>). Powód: <reason></red>", config.messages().privateMuted());
        assertEquals(config.messages().privateMuted(), config.messages().playerMuteNotification());
        assertEquals("na zawsze", config.messages().muteTimePermanent());
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

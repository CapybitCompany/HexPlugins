package hex.limbo.config;

import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.prompt.AuthReason;
import hex.limbo.prompt.PromptService;
import hex.limbo.testsupport.FakeConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrade scenarios: a data directory that still holds the files a pre-{@code config-version 2}
 * release wrote must come out of startup fully current, without losing anything the operator had
 * changed. The old files are the ones actually shipped by that release, bundled as
 * {@code legacy/*-v1.yml}, so these tests exercise the real upgrade path rather than a mock-up.
 */
class ConfigMigrationTest {

    private ConfigLoader loader(Path dir) {
        return new ConfigLoader(dir, LoggerFactory.getLogger(ConfigMigrationTest.class));
    }

    private ConfigMigrator migrator() {
        return new ConfigMigrator(LoggerFactory.getLogger(ConfigMigrationTest.class));
    }

    /** Drops the untouched v1 files into the data directory, as an upgrading server would have. */
    private void installV1(Path dir) throws IOException {
        Files.createDirectories(dir);
        copyResource("legacy/config-v1.yml", dir.resolve("config.yml"));
        copyResource("legacy/messages-v1.yml", dir.resolve("messages.yml"));
    }

    private void copyResource(String resource, Path target) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "bundled resource missing: " + resource);
            Files.write(target, in.readAllBytes());
        }
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    // ------------------------------------------------------------------ messages.yml

    @Test
    void untouchedV1MessagesAreFullyRefreshed(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("messages.yml");

        ConfigMigrator.Result result = migrator().migrateMessages(file);

        assertTrue(result.migrated());
        assertEquals(1, result.fromVersion());
        assertEquals(ConfigMigrator.CURRENT_VERSION, result.toVersion());
        assertTrue(result.keptCustomKeys().isEmpty(), "nothing was customised, so nothing to keep");
        assertFalse(result.refreshedKeys().isEmpty(), "the old wording must have been refreshed");

        Map<String, String> migrated = ConfigMigrator.flattenFile(file);
        assertEquals("2", migrated.get(ConfigMigrator.MESSAGES_VERSION_KEY));
        assertEquals("&6&lHEX", migrated.get("prompts.login.title"));
        assertEquals("&7Zaloguj się: &f/login <hasło>", migrated.get("prompts.login.subtitle"));
        assertEquals("&a&lZalogowano pomyślnie!", migrated.get("prompts.success.title"));
        assertEquals("&7Witamy na &6Hex&7!", migrated.get("prompts.success.subtitle"));
    }

    @Test
    void migrationAddsTheKeysThisReleaseIntroduced(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("messages.yml");
        Map<String, String> before = ConfigMigrator.flattenFile(file);
        assertFalse(before.containsKey("prompts.session-success.title"), "precondition: v1 lacks it");
        assertFalse(before.containsKey("prompts.premium-success.title"), "precondition: v1 lacks it");

        migrator().migrateMessages(file);

        Map<String, String> after = ConfigMigrator.flattenFile(file);
        for (AuthReason reason : AuthReason.values()) {
            for (String key : new String[] {reason.chatKey(), reason.titleKey(), reason.subtitleKey()}) {
                assertTrue(after.containsKey(key), "migration must add " + key);
            }
        }
        assertEquals("&7Zalogowano przez &eaktywną sesję&7.", after.get("prompts.session-success.subtitle"));
        assertEquals("&7Zalogowano przez konto &6premium&7.", after.get("prompts.premium-success.subtitle"));
    }

    @Test
    void noMessageKeyIsEverRenderedAsItsOwnName(@TempDir Path dir) throws IOException {
        installV1(dir);
        MessagesConfig messages = loader(dir).loadMessages();

        // The whole point of the migration: a key that HexLimbo looks up must never fall back to
        // printing "prompts.premium-success.title" at the player.
        for (AuthReason reason : AuthReason.values()) {
            for (String key : new String[] {reason.chatKey(), reason.titleKey(), reason.subtitleKey()}) {
                assertNotEquals(key, messages.raw(key), "missing after migration: " + key);
                assertNotEquals(key, plain(messages.component(key)), "leaked key to the player: " + key);
            }
        }
        for (String key : new String[] {
                "prompts.login.title", "prompts.login.subtitle", "prompts.login.bossbar", "prompts.login.chat",
                "prompts.register.title", "prompts.register.subtitle", "prompts.register.bossbar",
                "prompts.register.chat"}) {
            assertNotEquals(key, messages.raw(key), "missing after migration: " + key);
        }
    }

    @Test
    void migratedMessagesRenderAsColouredComponents(@TempDir Path dir) throws IOException {
        installV1(dir);
        MessagesConfig messages = loader(dir).loadMessages();

        Component title = messages.component("prompts.success.title");
        assertEquals(NamedTextColor.GREEN, title.color());
        assertEquals(TextDecoration.State.TRUE, title.decoration(TextDecoration.BOLD));
        assertEquals("Zalogowano pomyślnie!", plain(title));

        Component subtitle = messages.component("prompts.login.subtitle");
        assertEquals(2, subtitle.children().size(), "grey explanation plus white command");
        assertEquals(NamedTextColor.GRAY, subtitle.children().get(0).color());
        assertEquals(NamedTextColor.WHITE, subtitle.children().get(1).color());
        assertFalse(plain(subtitle).contains("&"), "colour codes must be parsed, not printed");
        assertTrue(plain(subtitle).contains("<hasło>"), "the placeholder must stay visible");
    }

    @Test
    void polishCharactersSurviveTheMigration(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("messages.yml");
        migrator().migrateMessages(file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (String letter : new String[] {"ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"}) {
            assertTrue(text.contains(letter), "migration lost the Polish letter " + letter);
        }
        assertFalse(text.contains("�"), "migration produced mojibake");
        assertEquals("&8» &7Musisz się zalogować. Użyj: &f/login <hasło>",
                ConfigMigrator.flattenFile(file).get("prompts.login.chat"));
    }

    @Test
    void customisedMessagesAreKeptAndRebranded(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("messages.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        // The operator reworded two lines and invented one of their own.
        original = original.replace(
                "login.wrong-password: \"Nieprawidłowe hasło.\"",
                "login.wrong-password: \"&4To hasło jest błędne, spróbuj ponownie.\"");
        original = original.replace(
                "prompts.success.subtitle: \"Miłej gry na HexagonMC!\"",
                "prompts.success.subtitle: \"Baw się dobrze na HexagonMC, graczu!\"");
        original = original + "\nmy.own.key: \"Coś własnego\"\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        ConfigMigrator.Result result = migrator().migrateMessages(file);
        Map<String, String> after = ConfigMigrator.flattenFile(file);

        assertEquals("&4To hasło jest błędne, spróbuj ponownie.", after.get("login.wrong-password"),
                "a deliberately reworded message must survive verbatim");
        assertEquals("Baw się dobrze na Hex, graczu!", after.get("prompts.success.subtitle"),
                "a customised message keeps its wording but loses the old brand");
        assertEquals("Coś własnego", after.get("my.own.key"), "an operator's own key must not be deleted");
        assertTrue(result.keptCustomKeys().contains("login.wrong-password"));
        assertTrue(result.rebrandedKeys().contains("prompts.success.subtitle"));
        assertTrue(result.preservedUnknownKeys().contains("my.own.key"));

        // Untouched keys around them still got the new defaults.
        assertEquals("&6&lHEX", after.get("prompts.login.title"));
    }

    @Test
    void noHexagonMcSurvivesTheMigrationEvenWhenCustomised(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("messages.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                + "\ncustom.welcome: \"Witaj na HexagonMC!\"\n", StandardCharsets.UTF_8);

        migrator().migrateMessages(file);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("HexagonMC"), "the old brand must not survive anywhere");
        assertEquals("Witaj na Hex!", ConfigMigrator.flattenFile(file).get("custom.welcome"));
    }

    // ------------------------------------------------------------------ config.yml

    @Test
    void v1ConfigWithTheOldRedBossbarBecomesYellow(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        assertEquals("RED", ConfigMigrator.flattenFile(file).get("prompts.bossbar-color"),
                "precondition: v1 shipped a red BossBar");

        ConfigMigrator.Result result = migrator().migrateConfig(file);

        assertTrue(result.migrated());
        Map<String, String> after = ConfigMigrator.flattenFile(file);
        assertEquals("YELLOW", after.get("prompts.bossbar-color"));
        assertEquals("2", after.get(ConfigMigrator.CONFIG_VERSION_KEY));
        assertTrue(result.refreshedKeys().contains("prompts.bossbar-color"));
    }

    @Test
    void aDeliberatelyChosenBossbarColourIsKept(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("bossbar-color: \"RED\"", "bossbar-color: \"PURPLE\""), StandardCharsets.UTF_8);

        migrator().migrateConfig(file);

        assertEquals("PURPLE", ConfigMigrator.flattenFile(file).get("prompts.bossbar-color"));
        assertEquals("PURPLE", loader(dir).loadConfig().prompts().bossbarColor());
    }

    @Test
    void migrationSplitsTheLegacyPremiumSkipFlag(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        // The operator had switched the old combined flag off.
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("premium-skip-enabled: true", "premium-skip-enabled: false"), StandardCharsets.UTF_8);

        migrator().migrateConfig(file);

        Map<String, String> after = ConfigMigrator.flattenFile(file);
        assertEquals("false", after.get("prompts.premium-success-enabled"),
                "the old off-switch must seed the premium flag");
        assertEquals("false", after.get("prompts.admin-bypass-success-enabled"),
                "the old off-switch must seed the admin-bypass flag");

        PluginConfig.Prompts prompts = loader(dir).loadConfig().prompts();
        assertFalse(prompts.premiumSuccessEnabled());
        assertFalse(prompts.adminBypassSuccessEnabled());
    }

    @Test
    void anUnmigratedFileStillHonoursTheLegacyFlagAsAnAlias(@TempDir Path dir) throws IOException {
        // A hand-written config that never went through the migrator: the alias has to hold.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), """
                config-version: 2
                prompts:
                  premium-skip-enabled: false
                """, StandardCharsets.UTF_8);

        PluginConfig.Prompts prompts = loader(dir).loadConfig().prompts();

        assertFalse(prompts.premiumSuccessEnabled());
        assertFalse(prompts.adminBypassSuccessEnabled());
    }

    @Test
    void newKeysWinOverTheLegacyAliasWhenBothArePresent(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), """
                config-version: 2
                prompts:
                  premium-skip-enabled: false
                  premium-success-enabled: true
                  admin-bypass-success-enabled: false
                """, StandardCharsets.UTF_8);

        PluginConfig.Prompts prompts = loader(dir).loadConfig().prompts();

        assertTrue(prompts.premiumSuccessEnabled(), "the explicit key must beat the legacy alias");
        assertFalse(prompts.adminBypassSuccessEnabled());
    }

    @Test
    void customisedConfigValuesSurviveIncludingLists(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        text = text.replace("target: \"lobby\"", "target: \"hub\"");
        text = text.replace("min-password-length: 8", "min-password-length: 12");
        text = text.replace("ip-hash-pepper: \"change-me-please-set-a-long-random-value\"",
                "ip-hash-pepper: \"a-real-secret-nobody-should-lose\"");
        text = text.replace("""
                  allowed-commands-unauthenticated:
                    - "login"
                    - "l"
                    - "register"
                    - "reg"
                    - "limbo"
                    - "premium"
                    - "cpw"
                    - "changepassword"
                    - "logout"
                """, """
                  allowed-commands-unauthenticated:
                    - "login"
                    - "register"
                """);
        Files.writeString(file, text, StandardCharsets.UTF_8);

        migrator().migrateConfig(file);
        PluginConfig config = loader(dir).loadConfig();

        assertEquals("hub", config.targetServer());
        assertEquals(12, config.security().minPasswordLength());
        assertEquals("a-real-secret-nobody-should-lose", config.security().ipHashPepper());
        assertEquals(java.util.Set.of("login", "register"), config.allowedCommandsUnauthenticated());
        // ...while the untouched BossBar colour still got refreshed.
        assertEquals("YELLOW", config.prompts().bossbarColor());
    }

    // ------------------------------------------------------------------ safety

    @Test
    void migrationBacksUpTheOriginalExactlyOnce(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ConfigMigrator.Result result = migrator().migrateConfig(file);

        Path backup = dir.resolve("config.yml.v1.bak");
        assertTrue(Files.exists(backup), "the pre-migration file must be recoverable");
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(backup, result.backup());

        // Running again changes nothing and writes no second backup.
        migrator().migrateConfig(file);
        try (var entries = Files.list(dir)) {
            long backups = entries.filter(p -> p.getFileName().toString().contains(".bak")).count();
            assertEquals(1, backups, "an idempotent run must not pile up backups");
        }
    }

    @Test
    void migrationIsIdempotent(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path config = dir.resolve("config.yml");
        Path messages = dir.resolve("messages.yml");

        migrator().migrateConfig(config);
        migrator().migrateMessages(messages);
        String configAfterFirst = Files.readString(config, StandardCharsets.UTF_8);
        String messagesAfterFirst = Files.readString(messages, StandardCharsets.UTF_8);

        for (int run = 0; run < 3; run++) {
            ConfigMigrator.Result c = migrator().migrateConfig(config);
            ConfigMigrator.Result m = migrator().migrateMessages(messages);
            assertFalse(c.migrated(), "config.yml must not be migrated twice");
            assertFalse(m.migrated(), "messages.yml must not be migrated twice");
        }
        assertEquals(configAfterFirst, Files.readString(config, StandardCharsets.UTF_8));
        assertEquals(messagesAfterFirst, Files.readString(messages, StandardCharsets.UTF_8));
    }

    @Test
    void reloadReadsTheMigratedValues(@TempDir Path dir) throws IOException {
        installV1(dir);
        ConfigLoader loader = loader(dir);

        // First startup migrates...
        RuntimeContext context = new RuntimeContext(loader.loadConfig(), loader.loadMessages());
        assertEquals("YELLOW", context.config().prompts().bossbarColor());

        // ...and /hexlimbo reload goes through the very same loader again.
        context.update(loader.loadConfig(), loader.loadMessages());

        assertEquals("YELLOW", context.config().prompts().bossbarColor());
        assertEquals("&6&lHEX", context.messages().raw("prompts.login.title"));
        assertEquals("&7Zalogowano przez konto &6premium&7.",
                context.messages().raw("prompts.premium-success.subtitle"));
        assertTrue(context.config().prompts().premiumSuccessEnabled());
        assertTrue(context.config().prompts().adminBypassSuccessEnabled());
    }

    @Test
    void reloadPicksUpAnEditMadeAfterTheMigration(@TempDir Path dir) throws IOException {
        installV1(dir);
        ConfigLoader loader = loader(dir);
        RuntimeContext context = new RuntimeContext(loader.loadConfig(), loader.loadMessages());

        Path messages = dir.resolve("messages.yml");
        Files.writeString(messages, Files.readString(messages, StandardCharsets.UTF_8)
                .replace("prompts.success.subtitle: \"&7Witamy na &6Hex&7!\"",
                        "prompts.success.subtitle: \"&7Miło Cię widzieć na &6Hex&7!\""),
                StandardCharsets.UTF_8);

        context.update(loader.loadConfig(), loader.loadMessages());

        assertEquals("&7Miło Cię widzieć na &6Hex&7!", context.messages().raw("prompts.success.subtitle"));
    }

    @Test
    void aFreshInstallIsWrittenAtTheCurrentVersionAndNeverMigrated(@TempDir Path dir) throws IOException {
        ConfigLoader loader = loader(dir);
        loader.loadConfig();
        loader.loadMessages();

        assertEquals(String.valueOf(ConfigMigrator.CURRENT_VERSION),
                ConfigMigrator.flattenFile(dir.resolve("config.yml")).get(ConfigMigrator.CONFIG_VERSION_KEY));
        assertEquals(String.valueOf(ConfigMigrator.CURRENT_VERSION),
                ConfigMigrator.flattenFile(dir.resolve("messages.yml")).get(ConfigMigrator.MESSAGES_VERSION_KEY));
        assertFalse(Files.exists(dir.resolve("config.yml.v1.bak")), "nothing to back up on a fresh install");
        assertFalse(migrator().migrateConfig(dir.resolve("config.yml")).migrated());
    }

    /**
     * The point of the whole exercise: after an upgrade, every authentication path still produces a
     * real, coloured, Polish greeting rather than a raw message key.
     */
    @Test
    void everyAuthPathStillGreetsCorrectlyAfterAnUpgrade(@TempDir Path dir) throws IOException {
        installV1(dir);
        ConfigLoader loader = loader(dir);
        RuntimeContext context = new RuntimeContext(loader.loadConfig(), loader.loadMessages());
        ConnectionRegistry connections = new ConnectionRegistry();
        PromptService service = new PromptService(context, connections, (interval, task) -> () -> { });

        assertEquals("Zalogowano pomyślnie!", plain(greet(service, connections, AuthReason.MANUAL_LOGIN).title()));
        assertEquals("Witamy na Hex!", plain(greet(service, connections, AuthReason.MANUAL_LOGIN).subtitle()));
        assertEquals("Witamy na Hex!", plain(greet(service, connections, AuthReason.REGISTER).subtitle()));
        assertEquals("Zalogowano przez aktywną sesję.", plain(greet(service, connections, AuthReason.SESSION).subtitle()));
        assertEquals("Zalogowano przez konto premium.", plain(greet(service, connections, AuthReason.PREMIUM).subtitle()));
        assertEquals("Poczekalnia pominięta (uprawnienie bypass).",
                plain(greet(service, connections, AuthReason.ADMIN_BYPASS).subtitle()));

        // ...and the limbo prompt itself, which a cracked player sees before any of that.
        FakeConnection limbo = FakeConnection.of("UpgradedCracked");
        service.showLimboPrompt(limbo.connect(connections), AuthState.Stage.AWAITING_LOGIN);
        assertEquals("HEX", plain(limbo.titles.get(0).title()));
        assertEquals("Zaloguj się: /login <hasło>", plain(limbo.titles.get(0).subtitle()));
    }

    @Test
    void premiumAndAdminGreetingsStayIndependentlySwitchableAfterAnUpgrade(@TempDir Path dir) throws IOException {
        installV1(dir);
        Path file = dir.resolve("config.yml");
        migrator().migrateConfig(file);
        // The operator now silences only the staff greeting.
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("admin-bypass-success-enabled: true", "admin-bypass-success-enabled: false"),
                StandardCharsets.UTF_8);

        ConfigLoader loader = loader(dir);
        RuntimeContext context = new RuntimeContext(loader.loadConfig(), loader.loadMessages());
        ConnectionRegistry connections = new ConnectionRegistry();
        PromptService service = new PromptService(context, connections, (interval, task) -> () -> { });

        assertTrue(context.config().prompts().premiumSuccessEnabled());
        assertFalse(context.config().prompts().adminBypassSuccessEnabled());

        FakeConnection premium = FakeConnection.of("UpgradedPremium");
        ConnectionHandle premiumHandle = premium.connect(connections);
        service.markAuthenticated(premiumHandle, AuthReason.PREMIUM);
        service.onArrivedAtTarget(premiumHandle);
        assertEquals(1, premium.titles.size(), "premium must still be welcomed");

        FakeConnection staff = FakeConnection.of("UpgradedStaff");
        ConnectionHandle staffHandle = staff.connect(connections);
        service.markAuthenticated(staffHandle, AuthReason.ADMIN_BYPASS);
        service.onArrivedAtTarget(staffHandle);
        assertTrue(staff.titles.isEmpty(), "the admin-bypass greeting must be silenced on its own");
    }

    /** Drives one authentication path to the lobby and returns the title that was shown. */
    private Title greet(PromptService service, ConnectionRegistry connections, AuthReason reason) {
        FakeConnection player = FakeConnection.of("Greet-" + reason + "-" + System.nanoTime());
        ConnectionHandle handle = player.connect(connections);
        service.markAuthenticated(handle, reason);
        service.onArrivedAtTarget(handle);
        service.endConnection(handle);
        return player.titles.get(0);
    }

    @Test
    void theVersionMarkerIsNeverServedAsAMessage(@TempDir Path dir) throws IOException {
        installV1(dir);
        MessagesConfig messages = loader(dir).loadMessages();

        assertFalse(messages.asMap().containsKey(ConfigMigrator.MESSAGES_VERSION_KEY),
                "bookkeeping must not be reachable as a message key");
    }

}

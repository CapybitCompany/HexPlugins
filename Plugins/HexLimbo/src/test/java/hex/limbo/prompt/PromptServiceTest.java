package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.testsupport.FakeConnection;
import hex.limbo.testsupport.TestConfigs;
import hex.limbo.text.LegacyText;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PromptService} headless: a {@link FakeConnection} plays both the connection
 * identity and the Adventure audience, and a fake scheduler captures the repeating reminder
 * lifecycle. No Velocity proxy.
 *
 * <p>Assertions compare the rendered component back to its legacy form, which verifies both the
 * plain text and the colour/decoration structure in one go.
 */
class PromptServiceTest {

    private final ConnectionRegistry connections = new ConnectionRegistry();

    private static Map<String, String> promptMessages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prompts.login.bossbar", "&6Hex &8» &7Zaloguj się: &f/login <hasło>");
        m.put("prompts.login.title", "&6&lHEX");
        m.put("prompts.login.subtitle", "&7Zaloguj się: &f/login <hasło>");
        m.put("prompts.login.chat", "&8» &7Musisz się zalogować. Użyj: &f/login <hasło>");
        m.put("prompts.register.bossbar", "&6Hex &8» &7Zarejestruj się: &f/register <hasło> <hasło>");
        m.put("prompts.register.title", "&6&lHEX");
        m.put("prompts.register.subtitle", "&7Zarejestruj się: &f/register <hasło> <hasło>");
        m.put("prompts.register.chat", "&8» &7Nie masz jeszcze konta. Użyj: &f/register <hasło> <hasło>");
        m.put("prompts.success.chat", "&8» &aZalogowano pomyślnie. &7Miłej gry na &6Hex&7!");
        m.put("prompts.success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.success.subtitle", "&7Witamy na &6Hex&7!");
        m.put("prompts.session-success.chat", "&8» &aZalogowano automatycznie &7przez &eaktywną sesję&7.");
        m.put("prompts.session-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.session-success.subtitle", "&7Zalogowano przez &eaktywną sesję&7.");
        m.put("prompts.premium-success.chat", "&8» &aZalogowano pomyślnie. &7Wykryto konto &6premium&7.");
        m.put("prompts.premium-success.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-success.subtitle", "&7Zalogowano przez konto &6premium&7.");
        m.put("prompts.premium-skip.chat", "&8» &aZalogowano pomyślnie. &7Poczekalnia pominięta.");
        m.put("prompts.premium-skip.title", "&a&lZalogowano pomyślnie!");
        m.put("prompts.premium-skip.subtitle", "&7Poczekalnia pominięta.");
        return m;
    }

    private RuntimeContext contextWith(PluginConfig config) {
        return new RuntimeContext(config, new MessagesConfig(promptMessages()));
    }

    private RuntimeContext defaultContext() {
        return contextWith(TestConfigs.defaultConfig());
    }

    /** Renders a component back to its legacy form so colour structure is asserted too. */
    private static String legacy(Component component) {
        return LegacyText.serialize(component);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // ---------------------------------------------------------------- limbo prompt

    @Test
    void registeredUnauthenticatedPlayerGetsLoginPrompt() {
        FakeConnection player = FakeConnection.of("Alice");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        service.showLimboPrompt(player.connect(connections), AuthState.Stage.AWAITING_LOGIN);

        assertEquals(1, player.shownBars.size());
        assertEquals("&6Hex &8» &7Zaloguj się: &f/login <hasło>", legacy(player.shownBars.get(0).name()));
        assertEquals(1, player.titles.size(), "the limbo title must be shown exactly once");
        assertEquals("&6&lHEX", legacy(player.titles.get(0).title()));
        assertEquals("&7Zaloguj się: &f/login <hasło>", legacy(player.titles.get(0).subtitle()));
        assertEquals("&8» &7Musisz się zalogować. Użyj: &f/login <hasło>", legacy(player.messages.get(0)));
    }

    @Test
    void unregisteredUnauthenticatedPlayerGetsRegisterPrompt() {
        FakeConnection player = FakeConnection.of("Bob");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        service.showLimboPrompt(player.connect(connections), AuthState.Stage.UNREGISTERED);

        assertEquals("&6Hex &8» &7Zarejestruj się: &f/register <hasło> <hasło>", legacy(player.shownBars.get(0).name()));
        assertEquals(1, player.titles.size(), "the limbo title must be shown exactly once");
        assertEquals("&6&lHEX", legacy(player.titles.get(0).title()));
        assertEquals("&7Zarejestruj się: &f/register <hasło> <hasło>", legacy(player.titles.get(0).subtitle()));
        assertEquals("&8» &7Nie masz jeszcze konta. Użyj: &f/register <hasło> <hasło>", legacy(player.messages.get(0)));
    }

    @Test
    void limboPromptKeepsAngleBracketPlaceholdersVisible() {
        FakeConnection player = FakeConnection.of("Carol");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        service.showLimboPrompt(player.connect(connections), AuthState.Stage.UNREGISTERED);

        // MiniMessage would eat <hasło> as a tag; the legacy parser must keep it verbatim.
        assertTrue(plain(player.titles.get(0).subtitle()).contains("<hasło> <hasło>"));
        assertTrue(plain(player.shownBars.get(0).name()).contains("<hasło>"));
        assertTrue(plain(player.messages.get(0)).contains("<hasło>"));
    }

    @Test
    void limboPromptRendersMultipleColoursNotLiteralCodes() {
        FakeConnection player = FakeConnection.of("Dave");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        service.showLimboPrompt(player.connect(connections), AuthState.Stage.AWAITING_LOGIN);

        Component subtitle = player.titles.get(0).subtitle();
        assertFalse(plain(subtitle).contains("&"), "colour codes must be parsed, not printed literally");
        // Grey explanation, white command: the two roles the colour scheme prescribes.
        assertEquals("&7Zaloguj się: &f/login <hasło>", legacy(subtitle));
    }

    @Test
    void repeatedLimboRoutingDoesNotDuplicatePromptState() {
        FakeConnection player = FakeConnection.of("Erin");
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), connections, scheduler);
        ConnectionHandle handle = player.connect(connections);

        assertTrue(service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN));
        assertFalse(service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN));
        assertFalse(service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN));

        assertEquals(1, player.shownBars.size(), "must never stack BossBars for one player");
        assertEquals(1, player.titles.size(), "must never re-fire the limbo title");
        assertEquals(1, scheduler.scheduledCount, "must never stack repeating reminder tasks");
    }

    // ------------------------------------------------- deferred success greeting

    @Test
    void manualLoginShowsNothingUntilTargetServerIsConfirmed() {
        FakeConnection player = FakeConnection.of("Frank");
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), connections, scheduler);
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        int titlesInLimbo = player.titles.size();
        int messagesInLimbo = player.messages.size();

        assertTrue(service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN));

        // Prompt is gone immediately...
        assertFalse(service.hasActivePrompt(handle));
        assertEquals(1, player.hiddenBars.size(), "BossBar must be hidden the moment auth succeeds");
        assertEquals(1, scheduler.cancelCount, "reminder task must be cancelled the moment auth succeeds");
        // ...but nothing new is on screen yet, because the player is still in the limbo.
        assertEquals(titlesInLimbo, player.titles.size(), "success title must not fire before the transfer");
        assertEquals(messagesInLimbo, player.messages.size(), "success chat must not fire before the transfer");
        assertEquals(Optional.of(AuthReason.MANUAL_LOGIN), service.pendingLobbyGreeting(handle));

        service.onArrivedAtTarget(handle);

        assertEquals(titlesInLimbo + 1, player.titles.size());
        Title success = player.titles.get(player.titles.size() - 1);
        assertEquals("&a&lZalogowano pomyślnie!", legacy(success.title()));
        assertEquals("&7Witamy na &6Hex&7!", legacy(success.subtitle()));
        assertEquals("&8» &aZalogowano pomyślnie. &7Miłej gry na &6Hex&7!",
                legacy(player.messages.get(player.messages.size() - 1)));
    }

    @Test
    void registerUsesTheSameSuccessGreetingAsManualLogin() {
        FakeConnection player = FakeConnection.of("Gina");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.UNREGISTERED);
        service.onAuthenticated(handle, AuthReason.REGISTER);
        assertEquals(1, player.titles.size(), "no success title while still in the limbo");

        service.onArrivedAtTarget(handle);

        Title success = player.titles.get(player.titles.size() - 1);
        assertEquals("&a&lZalogowano pomyślnie!", legacy(success.title()));
        assertEquals("&7Witamy na &6Hex&7!", legacy(success.subtitle()));
    }

    @Test
    void sessionAutoLoginGetsItsOwnGreeting() {
        FakeConnection player = FakeConnection.of("Hank");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        // Session players never enter the limbo: the login pipeline marks them directly.
        assertTrue(service.markAuthenticated(handle, AuthReason.SESSION));
        assertTrue(player.titles.isEmpty(), "marking must not put anything on screen");

        service.onArrivedAtTarget(handle);

        assertEquals(1, player.titles.size());
        assertEquals("&a&lZalogowano pomyślnie!", legacy(player.titles.get(0).title()));
        assertEquals("&7Zalogowano przez &eaktywną sesję&7.", legacy(player.titles.get(0).subtitle()));
        assertEquals("&8» &aZalogowano automatycznie &7przez &eaktywną sesję&7.", legacy(player.messages.get(0)));
    }

    @Test
    void premiumLoginGetsItsOwnGreeting() {
        FakeConnection player = FakeConnection.of("Ivan");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.markAuthenticated(handle, AuthReason.PREMIUM);
        service.onArrivedAtTarget(handle);

        assertEquals(1, player.titles.size());
        assertEquals("&a&lZalogowano pomyślnie!", legacy(player.titles.get(0).title()));
        assertEquals("&7Zalogowano przez konto &6premium&7.", legacy(player.titles.get(0).subtitle()));
        assertEquals("&8» &aZalogowano pomyślnie. &7Wykryto konto &6premium&7.", legacy(player.messages.get(0)));
    }

    @Test
    void adminBypassKeepsTheLimboSkippedGreeting() {
        FakeConnection player = FakeConnection.of("Judy");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.markAuthenticated(handle, AuthReason.ADMIN_BYPASS);
        service.onArrivedAtTarget(handle);

        assertEquals("&7Poczekalnia pominięta.", legacy(player.titles.get(0).subtitle()));
        assertEquals("&8» &aZalogowano pomyślnie. &7Poczekalnia pominięta.", legacy(player.messages.get(0)));
    }

    @Test
    void everyReasonUsesADistinctSubtitleExceptLoginAndRegister() {
        for (AuthReason reason : AuthReason.values()) {
            FakeConnection player = FakeConnection.of("Reason-" + reason);
            PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
            ConnectionHandle handle = player.connect(connections);
            service.markAuthenticated(handle, reason);
            assertEquals(Optional.of(reason), service.onArrivedAtTarget(handle));
            assertEquals(1, player.titles.size(), reason + " must produce exactly one title");
            assertFalse(plain(player.titles.get(0).subtitle()).startsWith("prompts."),
                    reason + " is missing its subtitle message key");
        }
    }

    // --------------------------------------------------------- exactly-once + cleanup

    @Test
    void successGreetingFiresExactlyOncePerAuthentication() {
        FakeConnection player = FakeConnection.of("Karl");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        service.onArrivedAtTarget(handle);
        // A duplicate ServerConnectedEvent, or a later hop back to the lobby, must stay silent.
        assertEquals(Optional.empty(), service.onArrivedAtTarget(handle));
        assertEquals(Optional.empty(), service.onArrivedAtTarget(handle));

        assertEquals(1, player.titles.size(), "success title must appear exactly once");
        assertEquals(1, player.messages.size(), "success chat must appear exactly once");
    }

    @Test
    void arrivingAtTargetWithoutPendingGreetingIsSilent() {
        FakeConnection player = FakeConnection.of("Lena");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        assertEquals(Optional.empty(), service.onArrivedAtTarget(player.connect(connections)));

        assertTrue(player.titles.isEmpty());
        assertTrue(player.messages.isEmpty());
    }

    @Test
    void disconnectClearsPromptAndPendingGreeting() {
        FakeConnection player = FakeConnection.of("Mona");
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), connections, scheduler);
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        service.onAuthenticated(handle, AuthReason.MANUAL_LOGIN);

        // Player quits (or the transfer failed) before ever reaching the lobby.
        connections.endIfCurrent(handle.uuid(), player);
        service.endConnection(handle);

        assertFalse(service.hasActivePrompt(handle));
        assertEquals(Optional.empty(), service.pendingLobbyGreeting(handle),
                "a stale pending title must not survive the disconnect");
        int titlesBefore = player.titles.size();
        service.onArrivedAtTarget(handle);
        assertEquals(titlesBefore, player.titles.size(), "cleared greeting must never fire later");
        assertEquals(0, service.trackedDisplays());
        assertEquals(0, connections.size());
    }

    @Test
    void endConnectionCancelsTaskAndHidesBossbar() {
        FakeConnection player = FakeConnection.of("Nils");
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), connections, scheduler);
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        service.endConnection(handle);

        assertFalse(service.hasActivePrompt(handle));
        assertEquals(1, player.hiddenBars.size());
        assertEquals(1, scheduler.cancelCount);
        // A second disconnect event is a harmless no-op (overlapping lifecycle events).
        service.endConnection(handle);
        assertEquals(1, player.hiddenBars.size());
    }

    // ------------------------------------------------------------------ config gates

    @Test
    void disabledPromptsShowNothing() {
        PluginConfig.Prompts off = new PluginConfig.Prompts(
                false, true, true, true, 15L, "YELLOW", "PROGRESS", 1.0f, true, true, true);
        RuntimeContext context = contextWith(TestConfigs.withPrompts(off));
        FakeConnection player = FakeConnection.of("Olga");
        PromptService service = new PromptService(context, connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.showLimboPrompt(handle, AuthState.Stage.AWAITING_LOGIN);
        service.markAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        service.onArrivedAtTarget(handle);

        assertTrue(player.shownBars.isEmpty());
        assertTrue(player.messages.isEmpty());
        assertTrue(player.titles.isEmpty());
        assertEquals(Optional.empty(), service.pendingLobbyGreeting(handle), "state must still be released");
    }

    @Test
    void successTitleDisabledStillSendsSuccessChat() {
        PluginConfig.Prompts noTitle = new PluginConfig.Prompts(
                true, true, true, true, 15L, "YELLOW", "PROGRESS", 1.0f, false, true, true);
        FakeConnection player = FakeConnection.of("Piotr");
        PromptService service = new PromptService(
                contextWith(TestConfigs.withPrompts(noTitle)), connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);

        service.markAuthenticated(handle, AuthReason.MANUAL_LOGIN);
        service.onArrivedAtTarget(handle);

        assertTrue(player.titles.isEmpty());
        assertEquals(1, player.messages.size());
    }

    @Test
    void bothLimboSkipGatesOffSilencesOnlyThoseTwoPaths() {
        RuntimeContext context = contextWith(TestConfigs.withPrompts(
                TestConfigs.promptsWithSkipGates(false, false)));

        for (AuthReason silenced : List.of(AuthReason.PREMIUM, AuthReason.ADMIN_BYPASS)) {
            assertTrue(greet(context, silenced).titles.isEmpty(), silenced + " must be silenced");
            assertTrue(greet(context, silenced).messages.isEmpty(), silenced + " must be silenced");
        }
        // The manual, register and session paths are not affected by these gates.
        for (AuthReason kept : List.of(AuthReason.MANUAL_LOGIN, AuthReason.REGISTER, AuthReason.SESSION)) {
            assertEquals(1, greet(context, kept).titles.size(), kept + " must still be greeted");
        }
    }

    @Test
    void premiumGreetingCanBeDisabledWithoutSilencingAdminBypass() {
        RuntimeContext context = contextWith(TestConfigs.withPrompts(
                TestConfigs.promptsWithSkipGates(false, true)));

        assertTrue(greet(context, AuthReason.PREMIUM).titles.isEmpty(), "premium must be silenced");
        assertEquals(1, greet(context, AuthReason.ADMIN_BYPASS).titles.size(),
                "admin bypass must keep its greeting");
    }

    @Test
    void adminBypassGreetingCanBeDisabledWithoutSilencingPremium() {
        RuntimeContext context = contextWith(TestConfigs.withPrompts(
                TestConfigs.promptsWithSkipGates(true, false)));

        assertTrue(greet(context, AuthReason.ADMIN_BYPASS).titles.isEmpty(), "admin bypass must be silenced");
        assertEquals(1, greet(context, AuthReason.PREMIUM).titles.size(),
                "premium must keep its greeting");
    }

    @Test
    void premiumGreetingIsOnByDefaultAndCarriesTheRequestedWording() {
        FakeConnection player = greet(defaultContext(), AuthReason.PREMIUM);

        assertEquals("&a&lZalogowano pomyślnie!", legacy(player.titles.get(0).title()));
        assertEquals("&7Zalogowano przez konto &6premium&7.", legacy(player.titles.get(0).subtitle()));
    }

    /** Runs one authentication path to the lobby and returns what the player saw. */
    private FakeConnection greet(RuntimeContext context, AuthReason reason) {
        FakeConnection player = FakeConnection.of("Greet-" + reason + "-" + System.nanoTime());
        PromptService service = new PromptService(context, connections, new FakeScheduler());
        ConnectionHandle handle = player.connect(connections);
        service.markAuthenticated(handle, reason);
        service.onArrivedAtTarget(handle);
        return player;
    }

    @Test
    void reminderDisabledWhenIntervalZero() {
        PluginConfig.Prompts noReminder = new PluginConfig.Prompts(
                true, true, true, true, 0L, "YELLOW", "PROGRESS", 1.0f, true, true, true);
        RuntimeContext context = contextWith(TestConfigs.withPrompts(noReminder));
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(context, connections, scheduler);

        service.showLimboPrompt(FakeConnection.of("Quinn").connect(connections), AuthState.Stage.AWAITING_LOGIN);

        assertEquals(0, scheduler.scheduledCount, "interval 0 must not schedule a repeating task");
    }

    @Test
    void bossbarUsesConfiguredColour() {
        FakeConnection player = FakeConnection.of("Rita");
        PromptService service = new PromptService(defaultContext(), connections, new FakeScheduler());

        service.showLimboPrompt(player.connect(connections), AuthState.Stage.AWAITING_LOGIN);

        assertEquals(BossBar.Color.YELLOW, player.shownBars.get(0).color());
    }

    private static final class FakeScheduler implements PromptService.Scheduler {
        int scheduledCount = 0;
        int cancelCount = 0;

        @Override
        public PromptService.PromptTask scheduleRepeating(long intervalSeconds, Runnable task) {
            scheduledCount++;
            return () -> cancelCount++;
        }
    }
}

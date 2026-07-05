package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.testsupport.TestConfigs;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link PromptService} headless: a recording {@link Audience} captures BossBar/title/chat
 * side effects, and a fake scheduler captures the repeating reminder lifecycle. No Velocity proxy.
 */
class PromptServiceTest {

    private static Map<String, String> promptMessages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prompts.login.bossbar", "Zaloguj się: /login <hasło>");
        m.put("prompts.login.title", "Zaloguj się");
        m.put("prompts.login.subtitle", "Wpisz /login <hasło>, aby wejść na serwer.");
        m.put("prompts.login.chat", "Musisz się zalogować. Użyj: /login <hasło>");
        m.put("prompts.register.bossbar", "Zarejestruj się: /register <hasło> <hasło>");
        m.put("prompts.register.title", "Zarejestruj konto");
        m.put("prompts.register.subtitle", "Wpisz /register <hasło> <hasło>, aby zacząć grę.");
        m.put("prompts.register.chat", "Nie masz jeszcze konta. Użyj: /register <hasło> <hasło>");
        m.put("prompts.success.chat", "Zalogowano pomyślnie. Miłej gry na HexagonMC!");
        m.put("prompts.success.title", "Zalogowano");
        m.put("prompts.success.subtitle", "Miłej gry na HexagonMC!");
        m.put("prompts.premium-skip.chat", "Wykryto konto premium. Poczekalnia pominięta.");
        m.put("prompts.premium-skip.title", "Konto premium");
        m.put("prompts.premium-skip.subtitle", "Poczekalnia pominięta. Miłej gry!");
        return m;
    }

    private RuntimeContext contextWith(PluginConfig config) {
        return new RuntimeContext(config, new MessagesConfig(promptMessages()));
    }

    private RuntimeContext defaultContext() {
        return contextWith(TestConfigs.defaultConfig());
    }

    private static String text(Component component) {
        return ((TextComponent) component).content();
    }

    @Test
    void registeredUnauthenticatedPlayerGetsLoginPrompt() {
        RecordingAudience audience = new RecordingAudience();
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), scheduler);

        service.showLimboPrompt(UUID.randomUUID(), audience, AuthState.Stage.AWAITING_LOGIN);

        assertEquals(1, audience.shownBars.size());
        assertEquals("Zaloguj się: /login <hasło>", text(audience.shownBars.get(0).name()));
        assertEquals("Zaloguj się", text(audience.titles.get(0).title()));
        assertEquals("Musisz się zalogować. Użyj: /login <hasło>", text(audience.messages.get(0)));
    }

    @Test
    void unregisteredUnauthenticatedPlayerGetsRegisterPrompt() {
        RecordingAudience audience = new RecordingAudience();
        PromptService service = new PromptService(defaultContext(), new FakeScheduler());

        service.showLimboPrompt(UUID.randomUUID(), audience, AuthState.Stage.UNREGISTERED);

        assertEquals("Zarejestruj się: /register <hasło> <hasło>", text(audience.shownBars.get(0).name()));
        assertEquals("Zarejestruj konto", text(audience.titles.get(0).title()));
        assertEquals("Nie masz jeszcze konta. Użyj: /register <hasło> <hasło>", text(audience.messages.get(0)));
    }

    @Test
    void onAuthenticatedRemovesPromptAndSendsSuccess() {
        RecordingAudience audience = new RecordingAudience();
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), scheduler);
        UUID uuid = UUID.randomUUID();

        service.showLimboPrompt(uuid, audience, AuthState.Stage.AWAITING_LOGIN);
        assertTrue(service.hasActivePrompt(uuid));

        service.onAuthenticated(uuid, audience);

        assertFalse(service.hasActivePrompt(uuid));
        assertEquals(1, audience.hiddenBars.size(), "BossBar must be hidden on auth success");
        assertEquals(1, scheduler.cancelCount, "reminder task must be cancelled on auth success");
        assertEquals("Zalogowano pomyślnie. Miłej gry na HexagonMC!",
                text(audience.messages.get(audience.messages.size() - 1)));
    }

    @Test
    void repeatedLimboRoutingDoesNotDuplicatePromptState() {
        RecordingAudience audience = new RecordingAudience();
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), scheduler);
        UUID uuid = UUID.randomUUID();

        service.showLimboPrompt(uuid, audience, AuthState.Stage.AWAITING_LOGIN);
        service.showLimboPrompt(uuid, audience, AuthState.Stage.AWAITING_LOGIN);
        service.showLimboPrompt(uuid, audience, AuthState.Stage.AWAITING_LOGIN);

        assertEquals(1, audience.shownBars.size(), "must never stack BossBars for one player");
        assertEquals(1, scheduler.scheduledCount, "must never stack repeating reminder tasks");
    }

    @Test
    void clearCancelsTaskAndHidesBossbar() {
        RecordingAudience audience = new RecordingAudience();
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(defaultContext(), scheduler);
        UUID uuid = UUID.randomUUID();

        service.showLimboPrompt(uuid, audience, AuthState.Stage.AWAITING_LOGIN);
        service.clear(uuid, audience);

        assertFalse(service.hasActivePrompt(uuid));
        assertEquals(1, audience.hiddenBars.size());
        assertEquals(1, scheduler.cancelCount);
        // Second clear is a harmless no-op (idempotent cleanup on overlapping lifecycle events).
        service.clear(uuid, audience);
        assertEquals(1, audience.hiddenBars.size());
    }

    @Test
    void disabledPromptsShowNothing() {
        PluginConfig.Prompts off = new PluginConfig.Prompts(
                false, true, true, true, 15L, "RED", "PROGRESS", 1.0f, true, true);
        RuntimeContext context = contextWith(configWithPrompts(off));
        RecordingAudience audience = new RecordingAudience();
        PromptService service = new PromptService(context, new FakeScheduler());

        service.showLimboPrompt(UUID.randomUUID(), audience, AuthState.Stage.AWAITING_LOGIN);

        assertTrue(audience.shownBars.isEmpty());
        assertTrue(audience.messages.isEmpty());
    }

    @Test
    void reminderDisabledWhenIntervalZero() {
        PluginConfig.Prompts noReminder = new PluginConfig.Prompts(
                true, true, true, true, 0L, "RED", "PROGRESS", 1.0f, true, true);
        RuntimeContext context = contextWith(configWithPrompts(noReminder));
        FakeScheduler scheduler = new FakeScheduler();
        PromptService service = new PromptService(context, scheduler);

        service.showLimboPrompt(UUID.randomUUID(), new RecordingAudience(), AuthState.Stage.AWAITING_LOGIN);

        assertEquals(0, scheduler.scheduledCount, "interval 0 must not schedule a repeating task");
    }

    @Test
    void premiumSkipGreetingSent() {
        RecordingAudience audience = new RecordingAudience();
        PromptService service = new PromptService(defaultContext(), new FakeScheduler());

        service.showPremiumSkip(audience);

        assertEquals("Wykryto konto premium. Poczekalnia pominięta.", text(audience.messages.get(0)));
    }

    private static PluginConfig configWithPrompts(PluginConfig.Prompts prompts) {
        PluginConfig base = TestConfigs.defaultConfig();
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                base.allowedCommandsUnauthenticated().stream().toList(),
                base.database(),
                base.session(),
                base.security(),
                base.premium(),
                base.limbo(),
                prompts
        );
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

    private static final class RecordingAudience implements Audience {
        final List<Component> messages = new ArrayList<>();
        final List<BossBar> shownBars = new ArrayList<>();
        final List<BossBar> hiddenBars = new ArrayList<>();
        final List<Title> titles = new ArrayList<>();

        @Override
        public void sendMessage(Component message) {
            messages.add(message);
        }

        @Override
        public void showBossBar(BossBar bar) {
            shownBars.add(bar);
        }

        @Override
        public void hideBossBar(BossBar bar) {
            hiddenBars.add(bar);
        }

        @Override
        public void showTitle(Title title) {
            titles.add(title);
        }
    }
}

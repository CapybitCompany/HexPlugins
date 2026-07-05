package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the lifecycle of the unauthenticated login/register prompts: the persistent BossBar, the
 * one-shot center-screen title, the initial chat line and the periodic chat reminder. State is
 * keyed by player UUID so that:
 *
 * <ul>
 *     <li>a player never ends up with two BossBars or two repeating reminder tasks after a
 *     reconnect, reload, or repeated limbo routing ({@link #showLimboPrompt} is idempotent per
 *     UUID);</li>
 *     <li>every exit path (authentication, disconnect, logout, timeout, transfer, unregister)
 *     explicitly tears the BossBar and task down via {@link #clear}.</li>
 * </ul>
 *
 * <p>The service is decoupled from Velocity: it renders to any Adventure {@link Audience} (a
 * {@code Player} is one) and schedules through the injected {@link Scheduler}, which keeps it unit
 * testable without a running proxy.
 */
public final class PromptService {

    /** Handle to a scheduled repeating task, so it can be cancelled during cleanup. */
    public interface PromptTask {
        void cancel();
    }

    /** Abstraction over the proxy scheduler so the service can be tested headless. */
    @FunctionalInterface
    public interface Scheduler {
        PromptTask scheduleRepeating(long intervalSeconds, Runnable task);
    }

    private static final class Handle {
        private final BossBar bossBar; // nullable when bossbar disabled
        private volatile PromptTask task; // nullable when reminder disabled

        Handle(BossBar bossBar) {
            this.bossBar = bossBar;
        }
    }

    private final RuntimeContext context;
    private final Scheduler scheduler;
    private final ConcurrentHashMap<UUID, Handle> handles = new ConcurrentHashMap<>();

    public PromptService(RuntimeContext context, Scheduler scheduler) {
        this.context = context;
        this.scheduler = scheduler;
    }

    /**
     * Shows the correct prompt (login vs register) for an unauthenticated player who just landed in
     * the limbo. Safe to call repeatedly: at most one BossBar and one reminder task exist per UUID.
     */
    public void showLimboPrompt(UUID uuid, Audience audience, AuthState.Stage stage) {
        PluginConfig.Prompts cfg = context.config().prompts();
        if (!cfg.enabled()) {
            return;
        }
        // computeIfAbsent runs the show-once side effects atomically per key: concurrent
        // ServerConnected events (reconnect races) can only produce a single prompt.
        handles.computeIfAbsent(uuid, id -> createAndShow(cfg, audience, stage));
    }

    private Handle createAndShow(PluginConfig.Prompts cfg, Audience audience, AuthState.Stage stage) {
        MessagesConfig messages = context.messages();
        BossBar bar = null;
        if (cfg.bossbarEnabled()) {
            bar = BossBar.bossBar(
                    PromptMessages.bossbar(messages, stage),
                    clampProgress(cfg.bossbarProgress()),
                    parseColor(cfg.bossbarColor()),
                    parseOverlay(cfg.bossbarOverlay()));
            audience.showBossBar(bar);
        }
        if (cfg.titleEnabled()) {
            audience.showTitle(Title.title(
                    PromptMessages.title(messages, stage),
                    PromptMessages.subtitle(messages, stage)));
        }
        if (cfg.chatEnabled()) {
            audience.sendMessage(PromptMessages.chat(messages, stage));
        }
        Handle handle = new Handle(bar);
        if (cfg.chatEnabled() && cfg.reminderIntervalSeconds() > 0) {
            handle.task = scheduler.scheduleRepeating(
                    cfg.reminderIntervalSeconds(),
                    () -> audience.sendMessage(PromptMessages.chat(context.messages(), stage)));
        }
        return handle;
    }

    /**
     * Removes any active prompt for the player and shows the configurable success/welcome message.
     * Call this the moment the player becomes authenticated, before routing them to the target.
     */
    public void onAuthenticated(UUID uuid, Audience audience) {
        clear(uuid, audience);
        PluginConfig.Prompts cfg = context.config().prompts();
        if (!cfg.enabled()) {
            return;
        }
        MessagesConfig messages = context.messages();
        audience.sendMessage(Component.text(messages.raw("prompts.success.chat")));
        if (cfg.successTitleEnabled()) {
            audience.showTitle(Title.title(
                    Component.text(messages.raw("prompts.success.title")),
                    Component.text(messages.raw("prompts.success.subtitle"))));
        }
    }

    /**
     * Greets a premium / admin-bypass player who skipped the auth flow entirely and arrived in the
     * lobby. Never touches the prompt state map (these players never had a BossBar).
     */
    public void showPremiumSkip(Audience audience) {
        PluginConfig.Prompts cfg = context.config().prompts();
        if (!cfg.enabled() || !cfg.premiumSkipEnabled()) {
            return;
        }
        MessagesConfig messages = context.messages();
        audience.sendMessage(Component.text(messages.raw("prompts.premium-skip.chat")));
        if (cfg.successTitleEnabled()) {
            audience.showTitle(Title.title(
                    Component.text(messages.raw("prompts.premium-skip.title")),
                    Component.text(messages.raw("prompts.premium-skip.subtitle"))));
        }
    }

    /** Cancels the reminder task and hides the BossBar for the player, if any prompt was active. */
    public void clear(UUID uuid, Audience audience) {
        Handle handle = handles.remove(uuid);
        if (handle == null) {
            return;
        }
        if (handle.task != null) {
            handle.task.cancel();
        }
        if (handle.bossBar != null && audience != null) {
            audience.hideBossBar(handle.bossBar);
        }
    }

    /** Test/diagnostic hook: whether a prompt is currently tracked for the player. */
    public boolean hasActivePrompt(UUID uuid) {
        return handles.containsKey(uuid);
    }

    private static float clampProgress(float progress) {
        if (Float.isNaN(progress) || progress < 0f) {
            return 0f;
        }
        return Math.min(progress, 1f);
    }

    private static BossBar.Color parseColor(String raw) {
        if (raw != null) {
            try {
                return BossBar.Color.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return BossBar.Color.RED;
    }

    private static BossBar.Overlay parseOverlay(String raw) {
        if (raw != null) {
            try {
                return BossBar.Overlay.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return BossBar.Overlay.PROGRESS;
    }
}

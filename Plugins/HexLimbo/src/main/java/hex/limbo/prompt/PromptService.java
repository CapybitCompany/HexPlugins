package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns everything the player sees around authentication: the limbo prompt (BossBar, one-shot
 * center-screen title, initial chat line and periodic reminder) and the success greeting shown
 * after the player has actually arrived on the target server.
 *
 * <h2>Scoped to a connection, not to a UUID</h2>
 * Display state is keyed by {@link ConnectionHandle#token()}, the strictly monotonic id
 * {@link ConnectionRegistry} hands out per physical connection. Two consequences fall out for free:
 *
 * <ul>
 *     <li>A late {@code DisconnectEvent} from an old socket removes only that socket's entry. The
 *     reconnect that already took over the UUID keeps its BossBar, reminder and pending greeting.</li>
 *     <li>An asynchronous {@code /login} that finishes after its player is gone holds the old
 *     handle, so it can neither revive display state nor overwrite the greeting a newer connection
 *     queued. Every mutating method re-checks {@link ConnectionRegistry#isCurrent} and reports
 *     whether it applied; callers must not ignore that.</li>
 * </ul>
 *
 * <p>The service never opens a connection of its own. If the registry does not know the handle,
 * nothing is created - that is what stops a {@code ServerConnectedEvent} arriving after a
 * disconnect from resurrecting a BossBar or a reminder task.
 *
 * <h2>Exactly-once display</h2>
 * <ul>
 *     <li>{@link #showLimboPrompt} claims its slot inside a {@code compute} block, so concurrent
 *     {@code ServerConnectedEvent}s can never stack two BossBars or two reminder tasks.</li>
 *     <li>{@link #onArrivedAtTarget} takes the pending greeting out of the entry in the same kind of
 *     block, so a duplicate event - or a later hop back to the lobby - stays silent.</li>
 * </ul>
 *
 * <p>Apart from the handle, the service is decoupled from Velocity: it renders to the handle's
 * Adventure {@link Audience} and schedules through the injected {@link Scheduler}, which keeps it
 * unit testable without a running proxy.
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

    /** The live limbo prompt: a BossBar and/or a repeating chat reminder. */
    private static final class Prompt {
        private final BossBar bossBar; // nullable when bossbar disabled
        private PromptTask task; // nullable when reminder disabled

        Prompt(BossBar bossBar) {
            this.bossBar = bossBar;
        }
    }

    /** Display state for one connection. Mutated only inside {@code ConcurrentHashMap} blocks. */
    private static final class Display {
        private Prompt prompt;
        private boolean promptShown;
        private AuthReason pendingGreeting;
    }

    private final RuntimeContext context;
    private final ConnectionRegistry connections;
    private final Scheduler scheduler;
    /** Keyed by connection token, never by UUID: two connections of one account must not alias. */
    private final ConcurrentHashMap<Long, Display> displays = new ConcurrentHashMap<>();

    public PromptService(RuntimeContext context, ConnectionRegistry connections, Scheduler scheduler) {
        this.context = context;
        this.connections = connections;
        this.scheduler = scheduler;
    }

    // ------------------------------------------------------------------ limbo prompt

    /**
     * Shows the correct prompt (login vs register) for an unauthenticated player who just landed in
     * the limbo. Safe to call repeatedly: at most one BossBar, one title and one reminder task
     * exist per connection.
     *
     * <p>Does nothing when the handle is no longer the current connection - a
     * {@code ServerConnectedEvent} that overtook a disconnect must not create anything.
     *
     * <p>The prompt is built inside the compute block on purpose. Sending to an Adventure audience
     * and scheduling a proxy task are both cheap and non-blocking, and doing it under the per-key
     * lock is what makes "show exactly once" hold without leaving a window in which a concurrent
     * teardown could orphan a half-created BossBar.
     *
     * @return whether a prompt was shown by this call
     */
    public boolean showLimboPrompt(ConnectionHandle handle, AuthState.Stage stage) {
        PluginConfig.Prompts cfg = context.config().prompts();
        if (!cfg.enabled() || !connections.isCurrent(handle)) {
            return false;
        }
        boolean[] shown = new boolean[1];
        displays.compute(handle.token(), (token, existing) -> {
            Display display = existing != null ? existing : new Display();
            if (display.promptShown) {
                return display;
            }
            // Re-check under the lock: a teardown that started before we got here has either
            // already removed the entry (existing == null and the connection is gone) or is about
            // to. Confirming currency here closes that window.
            if (!connections.isCurrent(handle)) {
                return existing;
            }
            display.promptShown = true;
            display.prompt = createAndShow(cfg, handle.audience(), stage);
            shown[0] = true;
            return display;
        });
        return shown[0];
    }

    private Prompt createAndShow(PluginConfig.Prompts cfg, Audience audience, AuthState.Stage stage) {
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
        Prompt prompt = new Prompt(bar);
        if (cfg.chatEnabled() && cfg.reminderIntervalSeconds() > 0) {
            prompt.task = scheduler.scheduleRepeating(
                    cfg.reminderIntervalSeconds(),
                    () -> audience.sendMessage(PromptMessages.chat(context.messages(), stage)));
        }
        return prompt;
    }

    // ------------------------------------------------------------------ success greeting

    /**
     * Call this the moment a player becomes authenticated by a command, before routing them
     * anywhere. It tears the limbo prompt down immediately (BossBar and reminder must not survive
     * the server switch) and remembers <em>why</em> the player is authenticated so the matching
     * greeting can be shown later, once the target server connection is confirmed.
     *
     * <p>No title is sent here on purpose: a title fired just before a backend switch is either
     * clobbered by the join sequence or shown while the player is still staring at the void.
     *
     * @return {@code false} when the connection is no longer current - the player disconnected or
     *         reconnected while the password was being verified, and nothing was changed
     */
    public boolean onAuthenticated(ConnectionHandle handle, AuthReason reason) {
        if (!connections.isCurrent(handle)) {
            return false;
        }
        Prompt[] taken = new Prompt[1];
        boolean[] applied = new boolean[1];
        displays.compute(handle.token(), (token, existing) -> {
            if (!connections.isCurrent(handle)) {
                return existing;
            }
            Display display = existing != null ? existing : new Display();
            taken[0] = display.prompt;
            display.prompt = null;
            display.promptShown = false;
            display.pendingGreeting = reason;
            applied[0] = true;
            return display;
        });
        tearDown(taken[0], handle.audience());
        return applied[0];
    }

    /**
     * Records a pending greeting for a player who never entered the limbo at all (premium
     * handshake, valid session, admin bypass). Called from the login pipeline with the handle that
     * pipeline was started for.
     *
     * @return {@code false} when the connection is no longer current and nothing was changed
     */
    public boolean markAuthenticated(ConnectionHandle handle, AuthReason reason) {
        return onAuthenticated(handle, reason);
    }

    /**
     * Releases the pending greeting after {@code ServerConnectedEvent} confirmed the player reached
     * the configured target server. The pending entry is taken atomically, so repeated or
     * concurrent events can only produce a single title. Returns the reason that was consumed, or
     * empty when there was nothing pending (e.g. the player simply hopped back to the lobby from
     * another backend, or the connection has ended).
     */
    public Optional<AuthReason> onArrivedAtTarget(ConnectionHandle handle) {
        if (!connections.isCurrent(handle)) {
            return Optional.empty();
        }
        AuthReason[] taken = new AuthReason[1];
        displays.computeIfPresent(handle.token(), (token, display) -> {
            taken[0] = display.pendingGreeting;
            display.pendingGreeting = null;
            return display;
        });
        AuthReason reason = taken[0];
        if (reason == null) {
            return Optional.empty();
        }
        PluginConfig.Prompts cfg = context.config().prompts();
        if (!cfg.enabled() || !isGreetingEnabled(cfg, reason)) {
            return Optional.of(reason);
        }
        MessagesConfig messages = context.messages();
        Audience audience = handle.audience();
        if (cfg.chatEnabled()) {
            audience.sendMessage(messages.component(reason.chatKey()));
        }
        if (cfg.successTitleEnabled()) {
            audience.showTitle(Title.title(
                    messages.component(reason.titleKey()),
                    messages.component(reason.subtitleKey())));
        }
        return Optional.of(reason);
    }

    /**
     * The two paths that skip the limbo are gated independently, so an admin can welcome premium
     * players while keeping staff joins silent (or the other way round). The {@code /login},
     * {@code /register} and session greetings are only gated by the master switch.
     */
    private static boolean isGreetingEnabled(PluginConfig.Prompts cfg, AuthReason reason) {
        return switch (reason) {
            case PREMIUM -> cfg.premiumSuccessEnabled();
            case ADMIN_BYPASS -> cfg.adminBypassSuccessEnabled();
            case MANUAL_LOGIN, REGISTER, SESSION -> true;
        };
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Tears the limbo prompt down but keeps the connection alive. This is the {@code /logout} path:
     * the player stays online and is routed back to the limbo, where {@link #showLimboPrompt} will
     * greet them again.
     */
    public void clearPrompt(ConnectionHandle handle) {
        Prompt[] taken = new Prompt[1];
        displays.computeIfPresent(handle.token(), (token, display) -> {
            taken[0] = display.prompt;
            display.prompt = null;
            display.promptShown = false;
            display.pendingGreeting = null;
            return display;
        });
        tearDown(taken[0], handle.audience());
    }

    /**
     * Releases everything this specific connection was showing. Keyed by token, so a disconnect
     * event from a superseded socket cannot touch the connection that replaced it.
     */
    public void endConnection(ConnectionHandle handle) {
        if (handle == null) {
            return;
        }
        Display removed = displays.remove(handle.token());
        if (removed != null) {
            tearDown(removed.prompt, handle.audience());
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /** Test/diagnostic hook: whether a limbo prompt is currently tracked for the connection. */
    public boolean hasActivePrompt(ConnectionHandle handle) {
        Display display = displays.get(handle.token());
        return display != null && display.prompt != null;
    }

    /** Test/diagnostic hook: the greeting queued for the connection's next target-server arrival. */
    public Optional<AuthReason> pendingLobbyGreeting(ConnectionHandle handle) {
        Display display = displays.get(handle.token());
        return display == null ? Optional.empty() : Optional.ofNullable(display.pendingGreeting);
    }

    /** Test/diagnostic hook: how many connections still hold display state. Must return to 0. */
    public int trackedDisplays() {
        return displays.size();
    }

    // ------------------------------------------------------------------ helpers

    private static void tearDown(Prompt prompt, Audience audience) {
        if (prompt == null) {
            return;
        }
        if (prompt.task != null) {
            prompt.task.cancel();
        }
        if (prompt.bossBar != null && audience != null) {
            audience.hideBossBar(prompt.bossBar);
        }
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
        return BossBar.Color.YELLOW;
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

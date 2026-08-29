package hex.limbo.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import hex.limbo.auth.AuthFlow;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import hex.limbo.auth.RouteCoordinator;
import hex.limbo.config.RuntimeContext;
import hex.limbo.prompt.PromptService;
import hex.limbo.security.IpHasher;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Turns {@code LoginEvent} into an {@link AuthFlow} call and applies the answer.
 *
 * <p>All the ordering that used to live here - which audits are written when, what happens if the
 * player leaves mid-lookup - now sits in {@link AuthFlow#resolveJoin}, so listener and tests
 * exercise the same code. What stays here is the part that genuinely belongs to Velocity:
 *
 * <ul>
 *     <li><b>Registering the connection synchronously.</b> {@link ConnectionRegistry#begin} runs in
 *     the event method itself, before {@link EventTask#async(Runnable)} dispatches any DB, Mojang or
 *     hashing work. From that moment the connection is addressable and a disconnect can invalidate
 *     it; were the handle created inside the async block instead, a disconnect would run its
 *     cleanup first and the worker would happily recreate state afterwards.</li>
 *     <li><b>Superseding a previous connection</b> for the same UUID, whose {@code DisconnectEvent}
 *     has not arrived yet.</li>
 *     <li><b>Arming the login timeout</b>, which is owned by the connection that scheduled it.</li>
 * </ul>
 *
 * <p>Players with the configured admin-bypass permission skip the auth flow but still get a
 * connection, so every other listener can reason about them the same way.
 */
public final class LoginListener {

    private final ProxyServer proxy;
    private final Object plugin;
    private final AuthService authService;
    private final ConnectionRegistry connections;
    private final AuthFlow flow;
    private final IpHasher ipHasher;
    private final RuntimeContext context;
    private final PromptService promptService;
    private final RouteCoordinator routes;
    private final Logger logger;

    public LoginListener(
            ProxyServer proxy,
            Object plugin,
            AuthService authService,
            AuthFlow flow,
            IpHasher ipHasher,
            RuntimeContext context,
            PromptService promptService,
            RouteCoordinator routes,
            Logger logger
    ) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.authService = authService;
        this.connections = authService.connections();
        this.flow = flow;
        this.ipHasher = ipHasher;
        this.context = context;
        this.promptService = promptService;
        this.routes = routes;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.LATE)
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        ConnectionRegistry.Registration registration = connections.begin(
                player.getUniqueId(), player.getUsername(), player, player);
        ConnectionHandle superseded = registration.superseded();
        if (superseded != null) {
            logger.debug("Superseding stale HexLimbo connection {} for {}", superseded, player.getUsername());
            connections.releaseSuperseded(superseded);
            promptService.endConnection(superseded);
            routes.endConnection(superseded);
        }
        ConnectionHandle handle = registration.handle();
        return EventTask.async(() -> handleLogin(event, player, handle));
    }

    private void handleLogin(LoginEvent event, Player player, ConnectionHandle handle) {
        AuthFlow.JoinRequest request = new AuthFlow.JoinRequest(
                player.isOnlineMode(),
                player.hasPermission(context.config().adminBypassPermission()),
                ipHasher.hash(player.getRemoteAddress().getAddress().getHostAddress()));

        AuthFlow.JoinResult result;
        try {
            result = flow.resolveJoin(handle, request);
        } catch (RuntimeException ex) {
            logger.error("HexLimbo login pipeline failed for {}: {}", player.getUsername(), ex.getMessage(), ex);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    context.messages().component("disconnect.account-state-error")));
            releaseConnection(handle);
            return;
        }

        if (result.denied()) {
            result.denyMessageKey().ifPresent(key -> event.setResult(
                    ResultedEvent.ComponentResult.denied(context.messages().component(key))));
            releaseConnection(handle);
            return;
        }
        if (!connections.isCurrent(handle)) {
            // The flow abandoned the join because the player left mid-pipeline. Nothing to arm.
            return;
        }
        if (!result.authenticated()) {
            scheduleLoginTimeout(handle);
        }
    }

    /**
     * Releases a connection we opened for a join that is being denied, rather than waiting for a
     * {@code DisconnectEvent} that may never carry this handle.
     */
    private void releaseConnection(ConnectionHandle handle) {
        if (connections.end(handle)) {
            promptService.endConnection(handle);
            routes.endConnection(handle);
        }
    }

    /**
     * Schedules the kick for a player who never authenticates. The task belongs to the connection
     * that scheduled it and is cancelled when that connection ends, so a timeout armed by an old
     * socket can never kick the reconnect that took the UUID over: before disconnecting anyone it
     * verifies both that its handle is still current and that the online player really is its own.
     */
    private void scheduleLoginTimeout(ConnectionHandle handle) {
        long timeoutSeconds = Math.max(10L, context.config().loginTimeoutSeconds());
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> proxy.getPlayer(handle.uuid())
                .filter(online -> connections.shouldTimeOut(handle, online))
                .ifPresent(online -> online.disconnect(
                        context.messages().component("disconnect.login-timeout"))))
                .delay(timeoutSeconds, TimeUnit.SECONDS).schedule();
        connections.attachLoginTimeout(handle, task::cancel);
    }

}

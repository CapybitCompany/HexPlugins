package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;

import java.util.Locale;
import java.util.Set;

/**
 * Blocks every command except the configured allowlist while a player is unauthenticated. Admin
 * bypass is honoured via the configured permission.
 *
 * <p>The gate is fail-closed: see
 * {@link hex.limbo.auth.ConnectionRegistry#isAuthenticatedConnection(java.util.UUID, Object)}.
 * Anything other than "this exact connection is registered and authenticated" is treated as
 * unauthenticated, so a socket the registry no longer knows stays blocked instead of inheriting
 * the privileges of the connection that replaced it.
 */
public final class CommandListener {

    private final AuthService authService;
    private final RuntimeContext context;

    public CommandListener(AuthService authService, RuntimeContext context) {
        this.authService = authService;
        this.context = context;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }
        if (player.hasPermission(context.config().adminBypassPermission())) {
            return;
        }
        // Fail-closed and identity-scoped: only this exact connection being registered AND
        // authenticated lets the command through. An unknown or superseded socket is blocked, and
        // is never judged against the auth state of whoever holds the UUID now.
        if (authService.connections().isAuthenticatedConnection(player.getUniqueId(), player)) {
            return;
        }

        String head = headOf(event.getCommand());
        Set<String> allowed = context.config().allowedCommandsUnauthenticated();
        if (allowed.contains(head)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(context.messages().component("error.command-blocked"));
    }

    public static String headOf(String fullCommand) {
        if (fullCommand == null) {
            return "";
        }
        String trimmed = fullCommand.trim();
        int space = trimmed.indexOf(' ');
        String first = space < 0 ? trimmed : trimmed.substring(0, space);
        if (first.startsWith("/")) {
            first = first.substring(1);
        }
        return first.toLowerCase(Locale.ROOT);
    }
}

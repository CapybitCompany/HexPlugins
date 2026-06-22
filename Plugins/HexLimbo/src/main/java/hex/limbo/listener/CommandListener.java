package hex.limbo.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import hex.limbo.auth.AuthService;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.text.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Blocks every command except the configured allowlist while a player is unauthenticated. Admin
 * bypass is honoured via the configured permission.
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
        if (authService.isAuthenticated(player.getUniqueId())) {
            return;
        }

        String head = headOf(event.getCommand());
        Set<String> allowed = context.config().allowedCommandsUnauthenticated();
        if (allowed.contains(head)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        player.sendMessage(Component.text(context.messages().raw("error.command-blocked")));
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

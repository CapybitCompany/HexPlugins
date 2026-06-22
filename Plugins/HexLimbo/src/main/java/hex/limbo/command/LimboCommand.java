package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;
import net.kyori.adventure.text.Component;

/**
 * Player-facing help: {@code /limbo help} prints a short cheat sheet of authentication commands.
 */
public final class LimboCommand implements SimpleCommand {

    private final RuntimeContext context;

    public LimboCommand(RuntimeContext context) {
        this.context = context;
    }

    @Override
    public void execute(Invocation invocation) {
        MessagesConfig messages = context.messages();
        String[] args = invocation.arguments();
        if (args.length == 0 || !"help".equalsIgnoreCase(args[0])) {
            invocation.source().sendMessage(Component.text(messages.raw("limbo.hint")));
            return;
        }
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-header")));
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-register")));
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-login")));
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-logout")));
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-changepassword")));
        invocation.source().sendMessage(Component.text(messages.raw("limbo.help-premium")));
    }
}

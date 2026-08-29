package hex.limbo.command;

import com.velocitypowered.api.command.SimpleCommand;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.RuntimeContext;

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
            invocation.source().sendMessage(messages.component("limbo.hint"));
            return;
        }
        invocation.source().sendMessage(messages.component("limbo.help-header"));
        invocation.source().sendMessage(messages.component("limbo.help-register"));
        invocation.source().sendMessage(messages.component("limbo.help-login"));
        invocation.source().sendMessage(messages.component("limbo.help-logout"));
        invocation.source().sendMessage(messages.component("limbo.help-changepassword"));
        invocation.source().sendMessage(messages.component("limbo.help-premium"));
    }
}

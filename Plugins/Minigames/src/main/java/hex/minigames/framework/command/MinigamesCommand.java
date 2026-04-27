package hex.minigames.framework.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public final class MinigamesCommand implements CommandExecutor {

    private final Runnable reloadAction;

    public MinigamesCommand(Runnable reloadAction) {
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadAction.run();
            sender.sendMessage("§aMinigames config reloaded.");
            return true;
        }

        sender.sendMessage("§eUżycie: /minigames reload");
        return true;
    }
}


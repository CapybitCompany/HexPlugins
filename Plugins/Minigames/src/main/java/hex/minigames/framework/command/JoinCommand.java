package hex.minigames.framework.command;

import hex.core.api.HexApi;
import hex.minigames.framework.GameInstance;
import hex.minigames.framework.InstanceManager;
import hex.minigames.framework.JoinResult;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;

public final class JoinCommand implements CommandExecutor, TabCompleter {

    private final HexApi api;
    private final Supplier<InstanceManager> managerSupplier;

    public JoinCommand(HexApi api, Supplier<InstanceManager> managerSupplier) {
        this.api = api;
        this.managerSupplier = managerSupplier;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§eUżycie: /join <gameType>");
            return true;
        }

        InstanceManager manager = managerSupplier.get();
        String gameType = args[0].toLowerCase();
        JoinResult result = manager.join(player, gameType);

        if (!result.success()) {
            api.ui().send(player, result.messageKey(), gameType);
            return true;
        }

        GameInstance instance = result.instance();
        api.ui().send(
                player,
                "lobby.join.ok",
                gameType,
                Integer.toString(instance.playersCount()),
                Integer.toString(instance.maxPlayers()),
                instance.worldName()
        );

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return managerSupplier.get().gameTypes();
        }
        return List.of();
    }
}

package hex.minigames.framework.command;

import hex.minigames.framework.GameInstance;
import hex.minigames.framework.InstanceManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;

public final class ArenaCommand implements CommandExecutor, TabCompleter {

    private final Supplier<InstanceManager> managerSupplier;

    public ArenaCommand(Supplier<InstanceManager> managerSupplier) {
        this.managerSupplier = managerSupplier;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUżycie: /arena <list|debug> <gameType>");
            return true;
        }

        InstanceManager manager = managerSupplier.get();
        String action = args[0].toLowerCase();
        String gameType = args[1].toLowerCase();
        List<GameInstance> instances = manager.instances(gameType);

        if (action.equals("list")) {
            sender.sendMessage("§6Instancje " + gameType + ": §f" + instances.size());
            for (GameInstance i : instances) {
                sender.sendMessage(" §7- §f" + i.instanceId() + " §8| §e" + i.state() + " §8| §b" + i.playersCount() + "/" + i.maxPlayers());
            }
            return true;
        }

        if (action.equals("debug")) {
            sender.sendMessage("§6Debug " + gameType + ":");
            for (GameInstance i : instances) {
                sender.sendMessage(" §7- id=§f" + i.instanceId()
                        + " §7state=§f" + i.state()
                        + " §7joinable=§f" + i.isJoinable()
                        + " §7map=§f" + i.worldName());
            }
            return true;
        }

        sender.sendMessage("§cNieznana akcja. Użyj list albo debug.");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("list", "debug");
        }
        if (args.length == 2) {
            return managerSupplier.get().gameTypes();
        }
        return List.of();
    }
}

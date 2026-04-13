package hex.minigames.framework.command;

import hex.core.api.HexApi;
import hex.minigames.framework.InstanceManager;
import hex.minigames.framework.PlayerSession;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

public final class LeaveCommand implements CommandExecutor {

    private final HexApi api;
    private final Supplier<InstanceManager> managerSupplier;

    public LeaveCommand(HexApi api, Supplier<InstanceManager> managerSupplier) {
        this.api = api;
        this.managerSupplier = managerSupplier;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        InstanceManager manager = managerSupplier.get();
        PlayerSession session = manager.session(player.getUniqueId()).orElse(null);
        if (session == null) {
            api.ui().send(player, "lobby.leave.none");
            return true;
        }

        manager.leave(player);
        api.ui().send(player, "lobby.leave.ok", session.gameTypeId());
        return true;
    }
}

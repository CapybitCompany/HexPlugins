package hex.panel2.command;

import hex.panel2.service.BuildSessionService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class HexPanel2StopCommand implements CommandExecutor {

    private final BuildSessionService buildSessionService;

    public HexPanel2StopCommand(BuildSessionService buildSessionService) {
        this.buildSessionService = buildSessionService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hex.panel2.stop")) {
            sender.sendMessage("§cBrak uprawnien do tej komendy.");
            return true;
        }

        boolean stopped = buildSessionService.stopEarly();
        if (!stopped) {
            sender.sendMessage("§eBudowanie nie jest aktualnie aktywne.");
            return true;
        }

        Bukkit.broadcastMessage("§cRunda budowania zostala zatrzymana przez administratora.");
        sender.sendMessage("§aBudowanie zostalo zatrzymane.");
        return true;
    }
}

package hex.core.command;

import hex.core.HexCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;


public class HexCoreVersion implements CommandExecutor {
    private static HexCore instance;

    public HexCoreVersion(HexCore plugin) {
        instance = plugin;
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String version = instance.getDescription().getVersion();
        sender.sendMessage("HexCore version" + version);
        return true;
    }
}

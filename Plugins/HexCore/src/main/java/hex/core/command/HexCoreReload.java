package hex.core.command;

import hex.core.api.HexApi;
import hex.core.api.config.ReloadResult;
import org.bukkit.command.*;

import java.util.List;

public class HexCoreReload implements CommandExecutor, TabCompleter {

    private final HexApi api;

    public HexCoreReload(HexApi api) {
        this.api = api;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§e/hexcore reload <id>");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (args.length < 2) {
                sender.sendMessage("§cUżycie: /hexcore reload <id>");
                return true;
            }

            String id = args[1];
            ReloadResult result = api.configs().reload(id);

            if (result.success()) {
                sender.sendMessage("§a" + result.message());
            } else {
                sender.sendMessage("§c" + result.message());
                for (String e : result.validationErrors()) {
                    sender.sendMessage("§7 - §c" + e);
                }
            }
            return true;
        }

        sender.sendMessage("§cNieznana komenda. §e/hexcore reload <id>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            // minimalnie: znane id (możesz to zrobić dynamicznie, jeśli dodasz listowanie w ConfigService)
            return List.of("flags", "ui");
        }
        return List.of();
    }
}

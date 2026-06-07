package hex.core.command;

import hex.core.HexCore;
import hex.core.api.config.ReloadResult;
import org.bukkit.command.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class HexCoreReload implements CommandExecutor, TabCompleter {

    private final HexCore plugin;

    public HexCoreReload(HexCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§aHexCore version " + plugin.getDescription().getVersion());
            sender.sendMessage("§e/hexcore reload <ui|flags|db|all>");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexcore.reload")) {
                sender.sendMessage("§cNie masz uprawnień: hexcore.reload");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cUżycie: /hexcore reload <ui|flags|db|all>");
                return true;
            }

            String id = args[1].toLowerCase(java.util.Locale.ROOT);
            ReloadResult result = switch (id) {
                case "ui" -> plugin.reloadUiConfig();
                case "flags" -> plugin.reloadFlagsConfig();
                case "configs", "config" -> plugin.reloadHotConfigs();
                case "db", "database" -> plugin.reloadDatabase();
                case "all" -> reloadAll();
                default -> ReloadResult.failed("Unknown reload target: " + id, List.of());
            };

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

        sender.sendMessage("§cNieznana komenda. §e/hexcore reload <ui|flags|db|all>");
        return true;
    }

    private ReloadResult reloadAll() {
        ReloadResult configs = plugin.reloadHotConfigs();
        if (!configs.success()) {
            return configs;
        }
        ReloadResult db = plugin.reloadDatabase();
        if (!db.success()) {
            return db;
        }
        return ReloadResult.ok("Reloaded ui, flags and database");
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return List.of("ui", "flags", "db", "all");
        }
        return List.of();
    }
}

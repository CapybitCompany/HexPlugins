package hex.restrictions.command;

import hex.restrictions.HexRestrictionsPlugin;
import hex.restrictions.service.RestrictionAudit;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RestrictionsCommand implements TabExecutor {
    private final HexRestrictionsPlugin plugin;

    public RestrictionsCommand(HexRestrictionsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexrestrictions.admin")) return true;
            plugin.reloadPlugin();
            sender.sendMessage("§aHexRestrictions reloaded.");
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("scan")) {
            if (!sender.hasPermission("hexrestrictions.admin")) return true;
            return handleScan(sender, args);
        }

        sender.sendMessage("§e/" + label + " <status|reload|scan players|scan loaded|scan <player>>");
        return true;
    }

    private boolean handleScan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e/hexrestrictions scan <players|loaded|player>");
            return true;
        }

        if (args[1].equalsIgnoreCase("players")) {
            RestrictionAudit total = RestrictionAudit.NONE;
            for (Player player : Bukkit.getOnlinePlayers()) {
                total = total.plus(plugin.restrictions().sanitizePlayer(player));
            }
            sender.sendMessage("§aScan graczy zakończony: items=" + total.removedItems()
                    + ", enchants=" + total.removedEnchantments());
            return true;
        }

        if (args[1].equalsIgnoreCase("loaded")) {
            plugin.worldScanner().queueLoadedChunks();
            sender.sendMessage("§aDodano załadowane chunki do skanowania. Kolejka: " + plugin.worldScanner().queuedChunks());
            return true;
        }

        Player player = Bukkit.getPlayerExact(args[1]);
        if (player == null) {
            sender.sendMessage("§cGracz nie jest online: " + args[1]);
            return true;
        }
        RestrictionAudit audit = plugin.restrictions().sanitizePlayer(player);
        sender.sendMessage("§aScan " + player.getName() + ": items=" + audit.removedItems()
                + ", enchants=" + audit.removedEnchantments());
        return true;
    }

    private void sendStatus(CommandSender sender) {
        var settings = plugin.restrictions().settings();
        sender.sendMessage("§6HexRestrictions §7v" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Enabled: §f" + settings.enabled());
        sender.sendMessage("§7Forbidden items: §f" + settings.forbiddenItems());
        sender.sendMessage("§7Forbidden enchantments: §f" + settings.forbiddenEnchantments());
        sender.sendMessage("§7Queued chunks: §f" + plugin.worldScanner().queuedChunks());
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return filter(List.of("status", "reload", "scan"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) {
            List<String> options = new ArrayList<>(List.of("players", "loaded"));
            Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            return filter(options, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(needle)).toList();
    }
}

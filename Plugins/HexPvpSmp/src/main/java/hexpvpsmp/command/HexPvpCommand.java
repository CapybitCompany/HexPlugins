package hexpvpsmp.command;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HexPvpCommand implements CommandExecutor, TabCompleter {

    private static final String PERM = "hexpvpsmp.admin";

    private final HexPvpSmpPlugin plugin;

    public HexPvpCommand(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERM) && !sender.isOp()) {
            sender.sendMessage(LegacyFormat.component(plugin.config().messages().noPermission()));
            return true;
        }
        if (args.length == 0) {
            usage(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender, args);
            case "untag" -> handleUntag(sender, args);
            case "regions" -> handleRegions(sender);
            case "debug" -> handleDebug(sender, args);
            default -> {
                usage(sender, label);
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        boolean ok = plugin.reloadPluginRuntime();
        sender.sendMessage(LegacyFormat.component(ok
                ? plugin.config().messages().reloadSuccess()
                : plugin.config().messages().reloadFailed()));
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUżycie: /hexpvp status <gracz>"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || target.getName() == null) {
            sender.sendMessage(LegacyFormat.component("&cNieznany gracz: &f" + args[1]));
            return true;
        }
        boolean tagged = plugin.combatTagService().isTagged(target.getUniqueId());
        int remaining = plugin.combatTagService().remainingSeconds(target.getUniqueId());
        if (tagged) {
            sender.sendMessage(LegacyFormat.component(
                    "&e" + target.getName() + " &7jest &cW WALCE &7(pozostało " + remaining + "s)"));
        } else {
            sender.sendMessage(LegacyFormat.component(
                    "&e" + target.getName() + " &7NIE jest &aw walce"));
        }
        return true;
    }

    private boolean handleUntag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUżycie: /hexpvp untag <gracz>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(LegacyFormat.component("&cGracz nie jest online: &f" + args[1]));
            return true;
        }
        boolean removed = plugin.combatTagService().untag(target.getUniqueId());
        sender.sendMessage(LegacyFormat.component(removed
                ? "&aZdjęto tag walki z &f" + target.getName()
                : "&7" + target.getName() + " nie był w walce."));
        return true;
    }

    private boolean handleRegions(CommandSender sender) {
        List<ProtectedRegion> regions = plugin.protectionService().allRegions();
        if (regions.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&7Brak skonfigurowanych regionów."));
            return true;
        }
        sender.sendMessage(LegacyFormat.component("&aRegiony (" + regions.size() + "):"));
        for (ProtectedRegion r : regions) {
            sender.sendMessage(LegacyFormat.component(String.format(Locale.US,
                    "&7- &f%s &7[%s] @ %s &7(X %.0f..%.0f, Z %.0f..%.0f, &owszystkie wysokości&7)",
                    r.id(), r.type(), r.world(),
                    r.cuboid().minX(), r.cuboid().maxX(),
                    r.cuboid().minZ(), r.cuboid().maxZ()
            )));
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUżycie: /hexpvp debug <on|off>"));
            return true;
        }
        boolean on = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            default -> false;
        };
        plugin.setRuntimeDebug(on);
        sender.sendMessage(LegacyFormat.component("&aTryb debug: &f" + (on ? "WŁĄCZONY" : "WYŁĄCZONY")));
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(LegacyFormat.component("&aHexPvpSmp:"));
        for (String line : new String[]{
                "&7/" + label + " reload &8- przeładuj konfigurację",
                "&7/" + label + " status <gracz> &8- sprawdź tag walki",
                "&7/" + label + " untag <gracz> &8- zdejmij tag walki",
                "&7/" + label + " regions &8- lista regionów",
                "&7/" + label + " debug <on|off> &8- logowanie debug"
        }) {
            sender.sendMessage(LegacyFormat.component(line));
        }
    }

    private static final List<String> TOP_LEVEL = List.of(
            "reload", "status", "untag", "regions", "debug");

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERM) && !sender.isOp()) {
            return List.of();
        }
        if (args.length == 1) {
            return prefix(TOP_LEVEL, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("status") || sub.equals("untag")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return prefix(names, args[1]);
            }
            if (sub.equals("debug")) {
                return prefix(List.of("on", "off"), args[1]);
            }
        }
        return List.of();
    }

    private List<String> prefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return options;
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String opt : options) {
            if (opt.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(opt);
            }
        }
        return out;
    }
}

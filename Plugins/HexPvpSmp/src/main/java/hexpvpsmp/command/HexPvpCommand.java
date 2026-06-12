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
            sender.sendMessage(LegacyFormat.component("&cYou do not have permission."));
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
                ? "&aHexPvpSmp reloaded."
                : "&cReload failed, check console."));
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexpvp status <player>"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || target.getName() == null) {
            sender.sendMessage(LegacyFormat.component("&cUnknown player: &f" + args[1]));
            return true;
        }
        boolean tagged = plugin.combatTagService().isTagged(target.getUniqueId());
        int remaining = plugin.combatTagService().remainingSeconds(target.getUniqueId());
        if (tagged) {
            sender.sendMessage(LegacyFormat.component(
                    "&e" + target.getName() + " &7is &cTAGGED &7(" + remaining + "s left)"));
        } else {
            sender.sendMessage(LegacyFormat.component(
                    "&e" + target.getName() + " &7is &aNOT tagged"));
        }
        return true;
    }

    private boolean handleUntag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexpvp untag <player>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(LegacyFormat.component("&cPlayer not online: &f" + args[1]));
            return true;
        }
        boolean removed = plugin.combatTagService().untag(target.getUniqueId());
        sender.sendMessage(LegacyFormat.component(removed
                ? "&aUntagged &f" + target.getName()
                : "&7" + target.getName() + " was not tagged."));
        return true;
    }

    private boolean handleRegions(CommandSender sender) {
        List<ProtectedRegion> regions = plugin.protectionService().allRegions();
        if (regions.isEmpty()) {
            sender.sendMessage(LegacyFormat.component("&7No regions configured."));
            return true;
        }
        sender.sendMessage(LegacyFormat.component("&aRegions (" + regions.size() + "):"));
        for (ProtectedRegion r : regions) {
            sender.sendMessage(LegacyFormat.component(String.format(Locale.US,
                    "&7- &f%s &7[%s] @ %s &7(%.0f,%.0f,%.0f .. %.0f,%.0f,%.0f)",
                    r.id(), r.type(), r.world(),
                    r.cuboid().minX(), r.cuboid().minY(), r.cuboid().minZ(),
                    r.cuboid().maxX(), r.cuboid().maxY(), r.cuboid().maxZ()
            )));
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LegacyFormat.component("&cUsage: /hexpvp debug <on|off>"));
            return true;
        }
        boolean on = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> true;
            default -> false;
        };
        plugin.setRuntimeDebug(on);
        sender.sendMessage(LegacyFormat.component("&aDebug now: &f" + (on ? "ON" : "OFF")));
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(LegacyFormat.component("&aHexPvpSmp:"));
        for (String line : new String[]{
                "&7/" + label + " reload",
                "&7/" + label + " status <player>",
                "&7/" + label + " untag <player>",
                "&7/" + label + " regions",
                "&7/" + label + " debug <on|off>"
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

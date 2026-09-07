package hex.parkour;

import hex.parkour.config.ParkourConfig;
import hex.parkour.model.ParkourArena;
import hex.parkour.service.ParkourSessionService;
import hex.parkour.util.Text;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class HexParkourCommand implements CommandExecutor, TabCompleter {
    private final Supplier<ParkourConfig> config;
    private final ParkourSessionService sessions;
    private final Runnable reload;

    public HexParkourCommand(Supplier<ParkourConfig> config, ParkourSessionService sessions, Runnable reload) {
        this.config = config;
        this.sessions = sessions;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hexparkour.admin")) {
            sender.sendMessage(Text.color("&cBrak uprawnien."));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            handleReload(sender);
            return true;
        }
        if ("tp".equalsIgnoreCase(args[0]) || "teleport".equalsIgnoreCase(args[0])) {
            handleTeleport(sender, args);
            return true;
        }
        sendUsage(sender);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("hexparkour.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("reload", "tp").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && ("tp".equalsIgnoreCase(args[0]) || "teleport".equalsIgnoreCase(args[0]))) {
            ParkourConfig current = config.get();
            if (current == null) return List.of();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            values.add("lobby");
            values.addAll(current.arenas().keySet());
            return values.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private void handleReload(CommandSender sender) {
        reload.run();
        ParkourConfig current = config.get();
        if (current != null && current.valid()) {
            sender.sendMessage(Text.color(current.message("reload-success", "&aPrzeladowano HexParkour.")));
        } else {
            sender.sendMessage(Text.color(current == null
                    ? "&cKonfiguracja HexParkour nie jest zaladowana."
                    : current.message("reload-failed", "&cKonfiguracja zawiera bledy.")));
        }
    }

    private void handleTeleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color(message("player-only", "&cTa komenda jest tylko dla gracza.")));
            return;
        }

        ParkourConfig current = config.get();
        if (current == null || !current.valid()) {
            sender.sendMessage(Text.color(current == null
                    ? "&cKonfiguracja HexParkour nie jest zaladowana."
                    : current.message("reload-failed", "&cKonfiguracja zawiera bledy.")));
            return;
        }

        World world = sessions.ensureWorldLoaded();
        if (world == null) {
            sender.sendMessage(Text.color(current.message("world-missing", "&cSwiat Parkoura nie jest zaladowany.")));
            return;
        }

        String target = args.length >= 2 ? args[1] : "lobby";
        if ("lobby".equalsIgnoreCase(target)) {
            if (sessions.enterStandaloneLobby(player)) {
                sender.sendMessage(Text.color(current.message("teleport-lobby", "&aPrzeniesiono do lobby Parkour.")));
            }
            return;
        }

        ParkourArena arena = arena(current, target);
        if (arena == null) {
            sender.sendMessage(Text.color(current.message("arena-unknown", "&cNieznana arena: {arena}")
                    .replace("{arena}", target)));
            return;
        }
        if (sessions.enterStandaloneArena(player, arena)) {
            sender.sendMessage(Text.color(current.message("teleport-arena", "&aPrzeniesiono na arene {arena}.")
                    .replace("{arena}", arena.id())
                    .replace("{arena_display}", arena.displayName())));
        }
    }

    private ParkourArena arena(ParkourConfig config, String id) {
        ParkourArena exact = config.arenas().get(id);
        if (exact != null) return exact;
        for (ParkourArena arena : config.arenas().values()) {
            if (arena.id().equalsIgnoreCase(id)) return arena;
        }
        return null;
    }

    private String message(String key, String fallback) {
        ParkourConfig current = config.get();
        return current == null ? fallback : current.message(key, fallback);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Text.color("&7/hexparkour reload"));
        sender.sendMessage(Text.color("&7/hexparkour tp [lobby|arena]"));
    }
}

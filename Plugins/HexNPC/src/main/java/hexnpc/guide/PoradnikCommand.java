package hexnpc.guide;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PoradnikCommand implements CommandExecutor, TabCompleter {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("miasto", "server_city"),
            Map.entry("minionki", "server_minions"),
            Map.entry("kolekcje", "server_collections"),
            Map.entry("ekonomia", "server_economy"),
            Map.entry("arcade", "arcade"),
            Map.entry("busdriver", "arcade_busdriver"),
            Map.entry("bandyta", "arcade_slot")
    );
    private final GuideMenuService service;

    public PoradnikCommand(GuideMenuService service) { this.service = service; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest przeznaczona dla graczy.");
            return true;
        }
        String target = GuideMenuService.ROOT_GUIDE;
        if (args.length > 0) target = ALIASES.getOrDefault(args[0].toLowerCase(Locale.ROOT), args[0]);
        service.open(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return ALIASES.keySet().stream().sorted().filter(v -> v.startsWith(prefix)).toList();
    }
}

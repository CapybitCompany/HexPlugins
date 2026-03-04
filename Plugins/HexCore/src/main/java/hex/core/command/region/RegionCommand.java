package hex.core.command.region;

import hex.core.api.HexApi;
import hex.core.api.region.BlockPos;
import hex.core.api.region.Region;
import hex.core.api.region.RegionKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

public final class RegionCommand implements CommandExecutor, TabCompleter {

    private final HexApi api;
    private final Plugin plugin;

    private final Map<UUID, Selection> selections = new HashMap<>();

    public RegionCommand(Plugin plugin, HexApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    private Selection sel(Player p) {
        return selections.computeIfAbsent(p.getUniqueId(), k -> new Selection());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use /region (needs positions).");
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        try {
            switch (sub) {

                case "pos1" -> {
                    Selection s = sel(p);
                    Location loc = p.getLocation();
                    s.setPos1(loc.getWorld().getName(), BlockPos.from(loc));

                    sender.sendMessage("§aUstawiono pos1: §f" + s.pos1().toCsv());

                    maybeStartPreview(p, s);
                    return true;
                }

                case "pos2" -> {
                    Selection s = sel(p);
                    Location loc = p.getLocation();
                    s.setPos2(loc.getWorld().getName(), BlockPos.from(loc));

                    sender.sendMessage("§aUstawiono pos2: §f" + s.pos2().toCsv());

                    maybeStartPreview(p, s);
                    return true;
                }

                case "create", "set" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUżycie: /region " + sub + " <namespace:id>");
                        return true;
                    }

                    RegionKey key = RegionKey.parse(args[1]);
                    Selection s = sel(p);

                    if (!s.isComplete()) {
                        sender.sendMessage("§cUstaw najpierw /region pos1 i /region pos2");
                        return true;
                    }

                    BlockPos min = BlockPos.min(s.pos1(), s.pos2());
                    BlockPos max = BlockPos.max(s.pos1(), s.pos2());

                    if (sub.equals("create") && api.regions().find(key).isPresent()) {
                        sender.sendMessage("§cRegion już istnieje: §f" + key);
                        return true;
                    }

                    Region region = new Region(key, s.world(), min, max, Map.of());

                    api.regions().upsert(region);
                    api.regions().save();

                    sender.sendMessage("§aZapisano region: §f" + key);

                    s.clear(); // czyści pos1/pos2 + wyłącza preview
                    sender.sendMessage("§7Selection wyczyszczona.");

                    return true;
                }

                case "delete", "del", "remove" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUżycie: /region delete <namespace:id>");
                        return true;
                    }

                    RegionKey key = RegionKey.parse(args[1]);
                    boolean ok = api.regions().delete(key);

                    if (ok) {
                        api.regions().save();
                        sender.sendMessage("§aUsunięto region: §f" + key);
                    } else {
                        sender.sendMessage("§cNie znaleziono regionu: §f" + key);
                    }

                    return true;
                }

                case "list" -> {
                    if (args.length >= 2) {
                        String ns = args[1];
                        var list = api.regions().listNamespace(ns);
                        sender.sendMessage("§eRegiony namespace §f" + ns + "§e: §f" + list.size());
                        for (var r : list) sender.sendMessage(" §7- §f" + r.key());
                    } else {
                        var list = api.regions().listAll();
                        sender.sendMessage("§eRegiony: §f" + list.size());
                        for (var r : list) sender.sendMessage(" §7- §f" + r.key());
                    }
                    return true;
                }

                case "info" -> {
                    if (args.length < 2) {
                        sender.sendMessage("§cUżycie: /region info <namespace:id>");
                        return true;
                    }

                    RegionKey key = RegionKey.parse(args[1]);
                    var opt = api.regions().find(key);

                    if (opt.isEmpty()) {
                        sender.sendMessage("§cNie znaleziono regionu: §f" + key);
                        return true;
                    }

                    Region r = opt.get();

                    sender.sendMessage("§eRegion §f" + r.key());
                    sender.sendMessage("§7World: §f" + r.world());
                    sender.sendMessage("§7Min: §f" + r.min().toCsv());
                    sender.sendMessage("§7Max: §f" + r.max().toCsv());

                    return true;
                }

                case "reload" -> {
                    api.regions().reload();
                    sender.sendMessage("§aPrzeładowano regions.yml");
                    return true;
                }

                case "clear" -> {
                    Selection s = sel(p);
                    s.clear();
                    sender.sendMessage("§aWyczyszczono pos1/pos2 i wyłączono podgląd.");
                    return true;
                }

                default -> {
                    help(sender);
                    return true;
                }
            }

        } catch (Exception ex) {
            sender.sendMessage("§cBłąd: §f" + ex.getMessage());
            return true;
        }
    }

    private void maybeStartPreview(Player p, Selection s) {
        if (!s.isComplete()) return;

        var w = Bukkit.getWorld(s.world());
        if (w == null) return;

        s.startPreview(plugin, p,
                () -> RegionPreview.drawCuboidEdges(p, w, s.pos1(), s.pos2(), 0.8),
                5L
        );

        p.sendMessage("§7Podgląd regionu: §aON §7(/region clear aby wyłączyć)");
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§e/region pos1");
        sender.sendMessage("§e/region pos2");
        sender.sendMessage("§e/region create <namespace:id>");
        sender.sendMessage("§e/region set <namespace:id>");
        sender.sendMessage("§e/region delete <namespace:id>");
        sender.sendMessage("§e/region list [namespace]");
        sender.sendMessage("§e/region info <namespace:id>");
        sender.sendMessage("§e/region reload");
        sender.sendMessage("§e/region clear");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {

        if (args.length == 1) {
            return List.of("pos1", "pos2", "create", "set", "delete",
                    "list", "info", "reload", "clear");
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            if (sub.equals("delete") || sub.equals("info") || sub.equals("set")) {
                return api.regions().listAll()
                        .stream()
                        .map(r -> r.key().toString())
                        .toList();
            }
        }

        return List.of();
    }
}
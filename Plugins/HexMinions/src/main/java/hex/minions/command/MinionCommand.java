package hex.minions.command;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.api.MinionMenuData;
import hex.minions.api.MinionView;
import hex.minions.api.TownMinionMenuData;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.diagnostics.ProjectDiagnosticsService;
import hex.minions.menu.MinionMenu;
import hex.minions.service.MinionItemFactory;
import hex.minions.robot.MiningRobotManager;
import hex.minions.service.MinionService;
import hex.minions.service.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinionCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService service;
    private final MinionItemFactory itemFactory;
    private final MinionMenu menu;
    private final MiningRobotManager robotManager;
    private final ProjectDiagnosticsService diagnostics;
    private final Runnable reloadAction;

    public MinionCommand(Plugin plugin, HexApi hex, MinionService service, MinionItemFactory itemFactory, MinionMenu menu, MiningRobotManager robotManager, ProjectDiagnosticsService diagnostics, Runnable reloadAction) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.itemFactory = itemFactory;
        this.menu = menu;
        this.robotManager = robotManager;
        this.diagnostics = diagnostics;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "wiki" -> wiki(sender, args);
            case "robots", "roboty" -> hiddenRobots(sender);
            case "pickup" -> playerAction(sender, args, "pickup");
            case "move" -> playerAction(sender, args, "move");
            case "select-index" -> selectIndex(sender, args);
            case "select" -> select(sender, args);
            case "action" -> action(sender, args);
            case "gui-action" -> guiAction(sender, args);
            case "reload" -> reload(sender);
            case "admin" -> admin(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (args.length < 3) { hex.ui().send(sender, "minions.usage.give"); return; }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) { hex.ui().send(sender, "minions.error.player-not-found", UiTokens.of("player", args[1])); return; }
        MinionTypeDefinition type = service.definitions().minionTypes().get(args[2]);
        if (type == null) { hex.ui().send(sender, "minions.error.unknown-type"); return; }
        int tier = args.length >= 4 ? parseInt(args[3], 1) : 1;
        int amount = args.length >= 5 ? parseInt(args[4], 1) : 1;
        ItemStack item = itemFactory.createMinionItem(type, Math.max(1, Math.min(tier, type.maxTier())), amount);
        target.getInventory().addItem(item).values().forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        hex.ui().send(sender, "minions.give.success", UiTokens.of("player", target.getName()).put("type", type.id()).put("amount", String.valueOf(amount)));
    }

    private void list(CommandSender sender) {
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        TownMinionMenuData data = service.townData(player);
        hex.ui().send(player, "minions.list.header", UiTokens.of("count", String.valueOf(data.minionCount())).put("limit", String.valueOf(data.minionLimit())));
        for (MinionMenuData minion : data.minions()) {
            hex.ui().send(player, "minions.list.line", UiTokens.of("id", minion.shortId()).put("name", readableName(minion.displayName())).put("tier", String.valueOf(minion.tier())).put("location", minion.world() + " " + minion.x() + "," + minion.y() + "," + minion.z()));
        }
    }

    private void playerAction(CommandSender sender, String[] args, String action) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        if (args.length < 2) { hex.ui().send(sender, "minions.usage.player-action", UiTokens.of("action", action)); return; }
        UUID id = parseUuid(args[1]);
        if (id == null) { hex.ui().send(sender, "minions.error.bad-id"); return; }
        CompletableFuture<OperationResult> future = action.equals("move") ? service.move(player, id, player.getLocation()) : service.pickup(player, id);
        future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> hex.ui().send(player, result.messageKey(), result.tokens())));
    }

    private void action(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        if (args.length < 3) { hex.ui().send(sender, "minions.usage.action"); return; }
        UUID id = parseUuid(args[2]);
        if (id == null) { hex.ui().send(sender, "minions.error.bad-id"); return; }
        CompletableFuture<OperationResult> future = switch (args[1].toLowerCase()) {
            case "collect" -> service.collect(player, id);
            case "upgrade" -> service.upgrade(player, id);
            case "pickup" -> service.pickup(player, id);
            case "move" -> service.move(player, id, player.getLocation());
            case "open" -> { menu.open(player, id); yield null; }
            default -> null;
        };
        if (future != null) future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> hex.ui().send(player, result.messageKey(), result.tokens())));
    }

    private void guiAction(CommandSender sender, String[] args) {
        // Ukryty bridge dla konfiguracji GUI, która potrafi wywołać wyłącznie komendę.
        // Gracz nie ma do niego dostępu; GUI uruchamia go jako konsola i przekazuje gracza,
        // więc właściwa logika MinionService nadal sprawdza jego członkostwo/COOP.
        if (sender instanceof Player && !sender.hasPermission("hexminions.admin")) {
            hex.ui().send(sender, "minions.error.no-permission");
            return;
        }
        if (args.length < 4) return;
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return;
        UUID id = parseUuid(args[3]);
        if (id == null) return;

        CompletableFuture<OperationResult> future = switch (args[2].toLowerCase()) {
            case "collect" -> service.collect(target, id);
            case "upgrade" -> service.upgrade(target, id);
            case "pickup" -> service.pickup(target, id);
            case "move" -> service.move(target, id, target.getLocation());
            case "open" -> {
                menu.open(target, id);
                yield null;
            }
            default -> null;
        };
        if (future != null) {
            future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin,
                    () -> hex.ui().send(target, result.messageKey(), result.tokens())));
        }
    }

    private void select(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        if (args.length < 2) { hex.ui().send(sender, "minions.usage.select"); return; }
        UUID id = parseUuid(args[1]);
        if (id == null) { hex.ui().send(sender, "minions.error.bad-id"); return; }
        menu.open(player, id);
    }

    private void selectIndex(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        int index = args.length >= 2 ? parseInt(args[1], 1) : 1;
        Optional<MinionMenuData> data = service.minionByIndex(player, index);
        if (data.isEmpty()) { hex.ui().send(player, "minions.error.not-found"); return; }
        menu.open(player, data.get().id());
    }

    private void wiki(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { hex.ui().send(sender, "minions.error.player-only"); return; }
        if (args.length >= 2 && args[1].equalsIgnoreCase("electronics")) {
            menu.openElectronicsWiki(player);
        } else if (args.length >= 2) {
            if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
            menu.openWikiType(player, args[1]);
        } else {
            menu.openWiki(player);
        }
    }

    private void sendHelp(CommandSender sender) {
        hex.ui().send(sender, "minions.help.player");
        if (sender.hasPermission("hexminions.admin")) hex.ui().send(sender, "minions.help.admin");
    }

    private void hiddenRobots(CommandSender sender) {
        // Robot górniczy pozostaje ukryty. Nie występuje w helpie ani tab-completion.
        sendHelp(sender);
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        reloadAction.run();
        hex.ui().send(sender, "minions.reload.success");
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hexminions.admin")) { hex.ui().send(sender, "minions.error.no-permission"); return; }
        if (args.length >= 2 && args[1].equalsIgnoreCase("metrics")) {
            hex.ui().send(sender, "minions.admin.metrics");
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("validate") || args[1].equalsIgnoreCase("waliduj"))) {
            var result = diagnostics.validateAndLog();
            sender.sendMessage("§a[HexMinions] Walidacja zakończona. §cBłędy: " + result.errors().size()
                    + "§7, §eostrzeżenia: " + result.warnings().size() + "§7, informacje: " + result.info().size() + '.');
            result.errors().stream().limit(10).forEach(line -> sender.sendMessage("§c- " + line));
            result.warnings().stream().limit(10).forEach(line -> sender.sendMessage("§e- " + line));
            if (result.errors().size() + result.warnings().size() > 20) sender.sendMessage("§7Pełna lista została wypisana do konsoli.");
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("balance-report") || args[1].equalsIgnoreCase("report") || args[1].equalsIgnoreCase("raport"))) {
            try {
                var path = diagnostics.generateBalanceReport();
                sender.sendMessage("§a[HexMinions] Wygenerowano raport: §f" + path.toAbsolutePath());
            } catch (Exception ex) {
                sender.sendMessage("§c[HexMinions] Nie udało się wygenerować raportu: " + ex.getMessage());
            }
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("addlimit") || args[1].equalsIgnoreCase("addminionlimit") || args[1].equalsIgnoreCase("addslots"))) {
            adminAddLimit(sender, args);
            return;
        }
        hex.ui().send(sender, "minions.admin.usage");
    }

    private void adminAddLimit(CommandSender sender, String[] args) {
        if (args.length < 4) { hex.ui().send(sender, "minions.admin.addlimit.usage"); return; }
        UUID townId = resolveTownId(args[2]);
        if (townId == null) { hex.ui().send(sender, "minions.admin.addlimit.town-not-found", UiTokens.of("town", args[2])); return; }
        int delta = parseInt(args[3], 0);
        if (delta == 0) { hex.ui().send(sender, "minions.admin.addlimit.zero"); return; }
        String source = args.length >= 5 ? args[4] : "console";
        service.addMinionLimitBonus(townId, delta, source);
        int max = service.maxMinions(townId);
        hex.ui().send(sender, "minions.admin.addlimit.success", UiTokens.of("delta", String.valueOf(delta)).put("town", townId.toString()).put("max", String.valueOf(max)).put("source", source));
    }

    private String readableName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.replaceAll("<[^>]+>", "");
    }

    private UUID resolveTownId(String raw) {
        UUID direct = parseUuid(raw);
        if (direct != null && service.findTown(direct).isPresent()) return direct;
        return service.findTownByName(raw).map(t -> t.id()).orElse(null);
    }

    private UUID parseUuid(String raw) {
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    private int parseInt(String raw, int def) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return def; }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean admin = sender.hasPermission("hexminions.admin");
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("help", "list", "wiki"));
            if (admin) values.addAll(List.of("give", "reload", "admin", "pickup", "move", "select", "select-index", "action"));
            return partial(args[0], values);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give") && admin) {
            return partial(args[2], new ArrayList<>(service.definitions().minionTypes().keySet()));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("wiki")) {
            ArrayList<String> values = new ArrayList<>();
            values.add("electronics");
            if (admin) values.addAll(service.definitions().minionTypes().keySet());
            return partial(args[1], values);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("action") && admin) {
            return partial(args[1], List.of("collect", "upgrade", "pickup", "move", "open"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && admin) {
            return partial(args[1], List.of("metrics", "validate", "balance-report", "addlimit", "addminionlimit", "addslots"));
        }
        return List.of();
    }

    private List<String> partial(String prefix, List<String> values) {
        String lower = prefix.toLowerCase();
        return values.stream().filter(v -> v.toLowerCase().startsWith(lower)).toList();
    }
}


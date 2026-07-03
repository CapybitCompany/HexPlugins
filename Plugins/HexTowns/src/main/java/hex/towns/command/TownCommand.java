package hex.towns.command;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.api.Page;
import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import hex.towns.service.OperationResult;
import hex.towns.gui.TownRenameAnvilListener;
import hex.towns.gui.TownCoopDecisionMenu;
import hex.towns.gui.NativeTownMenu;
import hex.towns.map.TownMapService;
import hex.towns.heart.TownHeartListener;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class TownCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final HexApi api;
    private final TownsService service;
    private final VisualCheckService visualCheckService;
    private volatile TownsConfig config;
    private final TownRenameAnvilListener renameGui;
    private final TownMapService mapService;
    private final TownCoopDecisionMenu coopDecisionMenu;
    private final TownHeartListener townHeartListener;
    private final NativeTownMenu nativeTownMenu;
    private final Map<UUID, PendingCreate> createConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, PendingToken> destroyConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, PendingToken> endCoopConfirmations = new ConcurrentHashMap<>();

    public TownCommand(Plugin plugin, HexApi api, TownsService service, VisualCheckService visualCheckService, TownsConfig config, TownRenameAnvilListener renameGui, TownMapService mapService, TownCoopDecisionMenu coopDecisionMenu, TownHeartListener townHeartListener, NativeTownMenu nativeTownMenu) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.visualCheckService = visualCheckService;
        this.config = config;
        this.renameGui = renameGui;
        this.mapService = mapService;
        this.coopDecisionMenu = coopDecisionMenu;
        this.townHeartListener = townHeartListener;
        this.nativeTownMenu = nativeTownMenu;
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                nativeTownMenu.openMain(player);
            } else {
                api.ui().send(sender, "towns.help");
            }
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            handleAdminReload(sender);
            return true;
        }
        if (sub.equals("admin")) {
            handleAdmin(sender, args);
            return true;
        }

        if (!(sender instanceof Player player)) {
            api.ui().send(sender, "towns.error.player-only");
            return true;
        }

        switch (sub) {
            case "menu" -> nativeTownMenu.openMain(player);
            case "manage" -> nativeTownMenu.openManage(player);
            case "claims" -> nativeTownMenu.openClaims(player);
            case "collections" -> nativeTownMenu.openCollections(player, hex.towns.gui.NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES);
            case "minions" -> nativeTownMenu.openMinions(player);
            case "danger" -> nativeTownMenu.openDanger(player);
            case "create" -> handleCreate(player, args);
            case "claim" -> handleAsync(player, service.claim(player));
            case "coop" -> handleAsync(player, service.requestCoop(player));
            case "accept" -> handleAccept(player, args);
            case "coopaccept" -> handleCoopAccept(player, args);
            case "coopreject" -> handleCoopReject(player, args);
            case "coopkick" -> handleCoopKick(player, args);
            case "coopdecide" -> handleCoopDecisionMenu(player, args);
            case "coopmember" -> handleCoopMemberMenu(player, args);
            case "endcoop", "leave" -> handleEndCoop(player, args);
            case "destroy" -> handleDestroy(player, args);
            case "rename", "name" -> handleRename(player, args);
            case "check" -> {
                boolean enabled = visualCheckService.toggle(player);
                api.ui().send(player, enabled ? "towns.check.on" : "towns.check.off");
            }
            case "info" -> handleInfo(player, args);
            case "here" -> handleHere(player);
            case "map" -> handleMap(player);
            case "growth" -> handleGrowth(player);
            default -> api.ui().send(player, "towns.help");
        }
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            UUID playerId = player.getUniqueId();
            PendingCreate pending = createConfirmations.get(playerId);
            if (pending == null || pending.expired()) {
                createConfirmations.remove(playerId);
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            if (!confirmationTokenAccepted(args, pending.token())) {
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            createConfirmations.remove(playerId);
            handleAsync(player, service.createTown(player, pending.name()));
            return;
        }

        String name = args.length >= 2 ? joinArgs(args, 1) : "";
        OperationResult preview = service.previewCreate(player, name);
        if (!preview.success()) {
            send(player, preview);
            return;
        }
        if (!config.creationConfirmRequired()) {
            handleAsync(player, service.createTown(player, name));
            return;
        }
        String token = token();
        createConfirmations.put(player.getUniqueId(), new PendingCreate(name, token, System.currentTimeMillis() + config.confirmWindowSeconds() * 1000L));
        api.ui().send(player, "towns.create.confirm", preview.tokens().put("token", token));
    }


    private void handleRename(Player player, String[] args) {
        if (args.length < 2) {
            renameGui.open(player);
            return;
        }
        handleAsync(player, service.renameTown(player, joinArgs(args, 1)));
    }

    private void handleAccept(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        Player requester = Bukkit.getPlayerExact(args[1]);
        if (requester == null) {
            api.ui().send(owner, "towns.error.player-not-found", UiTokens.of("player", args[1]));
            return;
        }
        handleAsync(owner, service.acceptCoop(owner, requester));
    }

    private void handleCoopAccept(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        OfflinePlayer requester = findOfflinePlayer(args[1]);
        handleAsync(owner, service.acceptCoopRequest(owner, requester.getUniqueId(), requester.getName()));
    }

    private void handleCoopReject(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        OfflinePlayer requester = findOfflinePlayer(args[1]);
        handleAsync(owner, service.rejectCoopRequest(owner, requester.getUniqueId(), requester.getName()));
    }

    private void handleCoopKick(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        OfflinePlayer target = findOfflinePlayer(args[1]);
        handleAsync(owner, service.kickCoopMember(owner, target.getUniqueId(), target.getName()));
    }

    private OfflinePlayer findOfflinePlayer(String raw) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Bukkit.getOfflinePlayer(raw);
        }
    }


    private void handleCoopDecisionMenu(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        OfflinePlayer requester = findOfflinePlayer(args[1]);
        coopDecisionMenu.openRequestDecision(owner, requester.getUniqueId(), requester.getName());
    }

    private void handleCoopMemberMenu(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        OfflinePlayer target = findOfflinePlayer(args[1]);
        coopDecisionMenu.openMemberKick(owner, target.getUniqueId(), target.getName());
    }

    private void handleEndCoop(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            UUID playerId = player.getUniqueId();
            PendingToken pending = endCoopConfirmations.get(playerId);
            if (pending == null || pending.expired()) {
                endCoopConfirmations.remove(playerId);
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            if (!confirmationTokenAccepted(args, pending.token())) {
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            endCoopConfirmations.remove(playerId);
            handleAsync(player, service.endCoop(player));
            return;
        }
        String token = token();
        endCoopConfirmations.put(player.getUniqueId(), new PendingToken(token, System.currentTimeMillis() + config.confirmWindowSeconds() * 1000L));
        api.ui().send(player, "towns.endcoop.warn", UiTokens.of("token", token));
    }

    private void handleDestroy(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            UUID playerId = player.getUniqueId();
            PendingToken pending = destroyConfirmations.get(playerId);
            if (pending == null || pending.expired()) {
                destroyConfirmations.remove(playerId);
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            if (!confirmationTokenAccepted(args, pending.token())) {
                api.ui().send(player, "towns.confirm.expired");
                return;
            }
            destroyConfirmations.remove(playerId);
            handleAsync(player, service.destroy(player));
            return;
        }
        String token = token();
        destroyConfirmations.put(player.getUniqueId(), new PendingToken(token, System.currentTimeMillis() + config.confirmWindowSeconds() * 1000L));
        api.ui().send(player, "towns.destroy.warn", UiTokens.of("token", token));
    }

    private void handleInfo(Player player, String[] args) {
        Town town;
        if (args.length >= 2) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            town = service.townIdOf(target.getUniqueId()).flatMap(service::findTown).orElse(null);
        } else {
            town = service.townIdOf(player.getUniqueId()).flatMap(service::findTown).orElse(null);
        }
        if (town == null) {
            api.ui().send(player, "towns.error.no-town");
            return;
        }
        String ownerName = Bukkit.getOfflinePlayer(town.ownerId()).getName();
        api.ui().send(player, "towns.info", UiTokens.of("town", town.name())
                .put("owner", ownerName == null ? town.ownerId().toString() : ownerName)
                .put("members", String.valueOf(service.membersOf(town).size()))
                .put("chunks", String.valueOf(service.chunksOf(town).size()))
                .put("growth", String.valueOf(town.growthPoints())));
    }

    private void handleHere(Player player) {
        service.townAt(player.getLocation()).ifPresentOrElse(
                town -> api.ui().send(player, "towns.here", UiTokens.of("town", town.name())),
                () -> api.ui().send(player, "towns.here.none")
        );
    }

    private void handleMap(Player player) {
        mapService.openMap(player);
    }

    private void handleGrowth(Player player) {
        Town town = service.townIdOf(player.getUniqueId()).flatMap(service::findTown).orElse(null);
        if (town == null) {
            api.ui().send(player, "towns.error.no-town");
            return;
        }
        api.ui().send(player, "towns.growth", UiTokens.of("growth", String.valueOf(town.growthPoints())));
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin")) {
            api.ui().send(sender, "towns.error.not-owner");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("metrics")) {
            api.db().async(service::countTowns).thenAccept(count -> Bukkit.getScheduler().runTask(plugin, () ->
                    api.ui().send(sender, "towns.admin.metrics", UiTokens.of("towns", String.valueOf(count)))));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            String cursor = args.length >= 3 ? args[2] : null;
            api.db().async(() -> service.listPage(cursor, 50)).thenAccept(page -> Bukkit.getScheduler().runTask(plugin, () -> sendAdminPage(sender, page)));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reload")) {
            handleAdminReload(sender);
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("addgrowth") || args[1].equalsIgnoreCase("growthadd"))) {
            handleAdminAddGrowth(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("giveheart")) {
            handleAdminGiveHeart(sender, args);
            return;
        }
        if (args.length >= 2 && (args[1].equalsIgnoreCase("syncgrowth") || args[1].equalsIgnoreCase("growthsync"))) {
            service.refreshGrowthFromDatabase().whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    Throwable cause = unwrapCompletion(error);
                    plugin.getLogger().log(Level.SEVERE, "HexTowns growth sync failed", cause);
                    String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                    api.ui().send(sender, "towns.error.db", UiTokens.of("error", message));
                    return;
                }
                if (result.skipped()) {
                    api.ui().send(sender, "towns.admin.growth-sync.skipped");
                    return;
                }
                api.ui().send(sender, "towns.admin.growth-sync", UiTokens.of("scanned", String.valueOf(result.scanned()))
                        .put("changed", String.valueOf(result.changed())));
            }));
            return;
        }
        api.ui().send(sender, "towns.help");
    }


    private void handleAdminReload(CommandSender sender) {
        if (!sender.hasPermission("hextowns.admin")) {
            api.ui().send(sender, "towns.error.not-owner");
            return;
        }
        try {
            if (plugin instanceof hex.towns.HexTownsPlugin townsPlugin) {
                townsPlugin.reloadTownsConfig();
            } else {
                plugin.reloadConfig();
            }
            api.ui().send(sender, "towns.admin.reload.success");
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "HexTowns config reload failed", ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            api.ui().send(sender, "towns.admin.reload.error", UiTokens.of("error", message));
        }
    }

    private void handleAdminAddGrowth(CommandSender sender, String[] args) {
        if (args.length < 4) {
            api.ui().send(sender, "towns.admin.addgrowth.usage");
            return;
        }
        Town town = resolveTown(args[2]);
        if (town == null) {
            api.ui().send(sender, "towns.admin.addgrowth.town-not-found", UiTokens.of("town", args[2]));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException ex) {
            api.ui().send(sender, "towns.admin.addgrowth.invalid-number");
            return;
        }
        if (amount == 0) {
            api.ui().send(sender, "towns.admin.addgrowth.zero");
            return;
        }
        String source = args.length >= 5 ? args[4] : "console";
        service.addGrowthPoints(town.id(), amount, source);
        api.ui().send(sender, "towns.admin.addgrowth.success", UiTokens.of("amount", String.valueOf(amount)).put("town", town.name()).put("source", source));
    }

    private void handleAdminGiveHeart(CommandSender sender, String[] args) {
        if (args.length < 3) {
            api.ui().send(sender, "towns.admin.giveheart.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            api.ui().send(sender, "towns.admin.giveheart.player-offline", UiTokens.of("player", args[2]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException ex) {
                api.ui().send(sender, "towns.admin.giveheart.invalid-number");
                return;
            }
        }
        townHeartListener.giveHeart(target, amount);
        api.ui().send(sender, "towns.admin.giveheart.success", UiTokens.of("amount", String.valueOf(amount)).put("player", target.getName()));
    }

    private Town resolveTown(String raw) {
        try {
            UUID townId = UUID.fromString(raw);
            return service.findTown(townId).orElse(null);
        } catch (IllegalArgumentException ignored) {
            final Town[] found = new Town[1];
            service.forEachTown(town -> {
                if (found[0] == null && town.name().equalsIgnoreCase(raw)) {
                    found[0] = town;
                }
            }, 100);
            return found[0];
        }
    }

    private void sendAdminPage(CommandSender sender, Page<Town> page) {
        for (Town town : page.items()) {
            api.ui().send(sender, "towns.map.line", UiTokens.of("line", town.internalId() + " " + town.name() + " " + town.id()));
        }
        if (page.nextCursor() != null) {
            api.ui().send(sender, "towns.map.line", UiTokens.of("line", "Next cursor: " + page.nextCursor()));
        }
    }

    private void handleAsync(Player player, CompletableFuture<OperationResult> future) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = unwrapCompletion(error);
                plugin.getLogger().log(Level.SEVERE, "HexTowns command failed for player " + player.getName(), cause);
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                api.ui().send(player, "towns.error.db", UiTokens.of("error", message));
                return;
            }
            send(player, result);
        }));
    }

    private Throwable unwrapCompletion(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private void send(Player player, OperationResult result) {
        api.ui().send(player, result.templateKey(), result.tokens());
    }


    private String joinArgs(String[] args, int from) {
        if (args.length <= from) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String token() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private boolean confirmationTokenAccepted(String[] args, String expectedToken) {
        return args.length < 3 || expectedToken.equals(args[2]) || "<token>".equalsIgnoreCase(args[2]);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("menu", "manage", "claims", "collections", "minions", "danger", "reload", "create", "claim", "coop", "accept", "coopaccept", "coopreject", "coopkick", "coopdecide", "coopmember", "endcoop", "destroy", "rename", "check", "info", "here", "map", "growth", "admin"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("metrics", "list", "reload", "addgrowth", "growthadd", "giveheart", "syncgrowth", "growthsync"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private record PendingCreate(String name, String token, long expiresAt) {
        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private record PendingToken(String token, long expiresAt) {
        boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }
}


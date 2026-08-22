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
import hex.towns.heart.TownHeartService;
import hex.towns.heart.TownHeartLocation;
import hex.towns.heart.TownHeartReconciliationService;
import hex.towns.heart.HeartReconciliationReport;
import hex.towns.heart.HeartVisualGroup;
import hex.towns.heart.HeartPurgeReport;
import hex.towns.heart.HeartFoundationReport;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
    private final TownHeartService townHeartService;
    private final TownHeartReconciliationService townHeartReconciliationService;
    private final NativeTownMenu nativeTownMenu;
    private final TownRefResolver townRefResolver;
    private final Map<UUID, PendingCreate> createConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, PendingToken> destroyConfirmations = new ConcurrentHashMap<>();
    private final Map<UUID, PendingToken> endCoopConfirmations = new ConcurrentHashMap<>();

    public TownCommand(Plugin plugin, HexApi api, TownsService service, VisualCheckService visualCheckService, TownsConfig config, TownRenameAnvilListener renameGui, TownMapService mapService, TownCoopDecisionMenu coopDecisionMenu, TownHeartListener townHeartListener, TownHeartService townHeartService, TownHeartReconciliationService townHeartReconciliationService, NativeTownMenu nativeTownMenu) {
        this.plugin = plugin;
        this.api = api;
        this.service = service;
        this.visualCheckService = visualCheckService;
        this.config = config;
        this.renameGui = renameGui;
        this.mapService = mapService;
        this.coopDecisionMenu = coopDecisionMenu;
        this.townHeartListener = townHeartListener;
        this.townHeartService = townHeartService;
        this.townHeartReconciliationService = townHeartReconciliationService;
        this.nativeTownMenu = nativeTownMenu;
        this.townRefResolver = new TownRefResolver(service);
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("townadmin")) {
            if (!sender.hasPermission("hextowns.admin")) {
                api.ui().send(sender, "towns.error.not-owner");
                return true;
            }
            String[] promoted = new String[args.length + 1];
            promoted[0] = "admin";
            System.arraycopy(args, 0, promoted, 1, args.length);
            handleAdmin(sender, promoted);
            return true;
        }
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
            if (!sender.hasPermission("hextowns.admin")) {
                api.ui().send(sender, "towns.error.not-owner");
                return true;
            }
            handleAdmin(sender, args);
            return true;
        }

        if (!(sender instanceof Player player)) {
            api.ui().send(sender, "towns.error.player-only");
            return true;
        }

        switch (sub) {
            case "menu" -> nativeTownMenu.openMain(player);
            case "guide", "poradnik" -> nativeTownMenu.openGuide(player);
            case "manage" -> nativeTownMenu.openManage(player);
            case "claims" -> nativeTownMenu.openClaims(player);
            case "collections" -> nativeTownMenu.openCollections(player, hex.towns.gui.NativeTownMenuHolder.Page.COLLECTIONS_RESOURCES);
            case "minions" -> nativeTownMenu.openMinions(player);
            case "danger" -> nativeTownMenu.openDanger(player);
            case "create" -> handleCreate(player, args);
            case "claim" -> handleAsync(player, service.claim(player));
            case "coop" -> {
                api.ui().send(player, "towns.coop.request-processing");
                handleAsync(player, service.requestCoop(player));
            }
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
            case "growth" -> nativeTownMenu.openGuideGrowth(player);
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
        handlePendingAccept(owner, args[1]);
    }

    private void handleCoopAccept(Player owner, String[] args) {
        if (args.length < 2) {
            api.ui().send(owner, "towns.help");
            return;
        }
        handlePendingAccept(owner, args[1]);
    }

    private void handlePendingAccept(Player owner, String rawRequester) {
        api.ui().send(owner, "towns.accept.processing");
        CompletableFuture<OperationResult> action = service.pendingCoopRequestAsync(owner, rawRequester)
                .thenCompose(request -> request
                        .map(pending -> service.acceptCoopRequest(owner, pending.playerId(), pending.name()))
                        .orElseGet(() -> CompletableFuture.completedFuture(OperationResult.fail("towns.accept.no-request"))));
        handleAsync(owner, action);
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
        if (args.length >= 2 && args[1].equalsIgnoreCase("find")) {
            handleAdminFind(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("tp")) {
            handleAdminTp(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cleanup")) {
            handleAdminCleanup(sender, args);
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("coop")) {
            handleAdminCoop(sender, args);
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
        if (args.length >= 2 && args[1].equalsIgnoreCase("heart")) {
            handleAdminHeart(sender, args);
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


    private void handleAdminCoop(CommandSender sender, String[] args) {
        if (args.length < 4 || !args[2].equalsIgnoreCase("debug")) {
            sender.sendMessage("§eUzycie: /townadmin coop debug <gracz|uuid>");
            return;
        }
        OfflinePlayer target = findOfflinePlayer(args[3]);
        UUID playerId = target.getUniqueId();
        sender.sendMessage("§6[HexTowns] member debug: " + (target.getName() == null ? args[3] : target.getName()) + " (" + playerId + ")");
        service.coopDebug(playerId).whenComplete((debug, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                sender.sendMessage("§c[HexTowns] member debug failed: " + unwrapCompletion(error).getMessage());
                return;
            }
            sender.sendMessage("§7runtimeMembership=§f" + (debug.runtimeMembership() == null ? "none" : debug.runtimeMembership()));
            sender.sendMessage("§7dbMembership=§f" + (debug.dbMembership() == null ? "none" : debug.dbMembership()));
            sender.sendMessage("§7pendingRequests=§f" + debug.requests().size());
            for (var request : debug.requests()) {
                sender.sendMessage("§7- town=§f" + request.townName() + "§7 (#" + request.townId() + ") age=§f" + formatDuration(request.ageMillis()));
            }
            if (debug.memberLimit() != null) {
                var limit = debug.memberLimit();
                sender.sendMessage("§7memberLimit cached/base=§f" + limit.cachedOrBaseLimit()
                        + " §7fresh=§f" + limit.fresh()
                        + " §7refreshing=§f" + limit.refreshing()
                        + " §7cacheAge=§f" + formatDuration(limit.estimatedCacheAgeMillis())
                        + " §7remaining=§f" + formatDuration(limit.cacheRemainingMillis())
                        + " §7runtimeMembers=§f" + debug.runtimeMemberCount());
            }
        }));
    }

    private String formatDuration(long millis) {
        if (millis < 0L) return "n/a";
        long seconds = millis / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return minutes > 0L ? minutes + "m " + rest + "s" : rest + "s";
    }

    private void handleAdminHeart(CommandSender sender, String[] args) {
        if (!townHeartReconciliationService.isReady()) {
            sender.sendMessage("§e[HexTowns] Dane miast nie sa jeszcze zaladowane. Sprobuj ponownie za chwile.");
            return;
        }
        if (args.length < 3) {
            sendHeartAdminUsage(sender);
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        switch (action) {
            case "scan" -> handleHeartScan(sender, args);
            case "purge-orphans" -> handleHeartPurgeOrphans(sender, args);
            case "purge-visual" -> handleHeartPurgeVisual(sender, args);
            case "rerender" -> handleHeartRerender(sender, args);
            case "cleanup-foundation" -> handleHeartFoundation(sender, args);
            default -> sendHeartAdminUsage(sender);
        }
    }

    private void handleHeartScan(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.heart.scan")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.heart.scan.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§eUzycie: /townadmin heart scan <nearby [radius]|loaded>");
            return;
        }
        HeartReconciliationReport report;
        if (args[3].equalsIgnoreCase("loaded")) {
            report = townHeartReconciliationService.scanLoaded();
        } else if (args[3].equalsIgnoreCase("nearby")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cSkan nearby wymaga gracza. Konsola moze uzyc: /townadmin heart scan loaded");
                return;
            }
            double radius = args.length >= 5 ? parseHeartRadius(args[4], 64.0D) : 64.0D;
            report = townHeartReconciliationService.scanNearby(player.getLocation(), radius);
        } else {
            sender.sendMessage("§eUzycie: /townadmin heart scan <nearby [radius]|loaded>");
            return;
        }
        sendHeartReport(sender, report);
    }

    private void handleHeartPurgeOrphans(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.heart.purge")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.heart.purge.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§eUzycie: /townadmin heart purge-orphans <nearby [radius]|loaded> [--dry-run]");
            return;
        }
        boolean dryRun = hasArg(args, "--dry-run");
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        HeartPurgeReport purge;
        if (args[3].equalsIgnoreCase("loaded")) {
            purge = townHeartReconciliationService.purgeOrphansLoaded(dryRun, actor);
        } else if (args[3].equalsIgnoreCase("nearby")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cPurge nearby wymaga gracza. Konsola moze uzyc scope loaded.");
                return;
            }
            double radius = 64.0D;
            if (args.length >= 5 && !args[4].startsWith("--")) radius = parseHeartRadius(args[4], 64.0D);
            purge = townHeartReconciliationService.purgeOrphansNearby(player.getLocation(), radius, dryRun, actor);
        } else {
            sender.sendMessage("§eUzycie: /townadmin heart purge-orphans <nearby [radius]|loaded> [--dry-run]");
            return;
        }
        sender.sendMessage("§6[HexTowns] orphan purge: matched=" + purge.matchedEntities() + " removed=" + purge.removedEntities() + " dryRun=" + purge.dryRun());
        sendHeartReport(sender, purge.scan());
    }

    private void handleHeartPurgeVisual(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.heart.purge")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.heart.purge.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§eUzycie: /townadmin heart purge-visual <townUuid> [--dry-run]");
            return;
        }
        UUID townId;
        try {
            townId = UUID.fromString(args[3]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cNieprawidlowy UUID miasta: " + args[3]);
            return;
        }
        boolean dryRun = hasArg(args, "--dry-run");
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        HeartPurgeReport purge = townHeartReconciliationService.purgeVisual(townId, dryRun, actor);
        sender.sendMessage("§6[HexTowns] purge-visual " + townId + ": matched=" + purge.matchedEntities()
                + " removed=" + purge.removedEntities() + " dryRun=" + purge.dryRun()
                + " §7(tylko aktualnie zaladowane chunki)");
    }

    private void handleHeartRerender(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.heart.rerender")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.heart.rerender.");
            return;
        }
        if (args.length < 4) {
            sender.sendMessage("§eUzycie: /townadmin heart rerender <townUuid|name>");
            return;
        }
        Town town = resolveAdminTown(sender, joinArgs(args, 3));
        if (town == null) return;
        boolean ok = townHeartService.rerender(town.id());
        if (ok) {
            UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
            service.audit(town.id(), actor, "HEART_ADMIN_RERENDER", "town=" + town.name());
        }
        sender.sendMessage(ok ? "§aPrzerenderowano serce miasta " + town.name() + "." : "§cMiasto nie ma aktywnego serca.");
    }

    private void handleHeartFoundation(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.heart.foundation")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.heart.foundation.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cCleanup fundamentu wymaga gracza stojacego w poblizu starego serca.");
            return;
        }
        if (args.length < 4 || !args[3].equalsIgnoreCase("nearby")) {
            sender.sendMessage("§eUzycie: /townadmin heart cleanup-foundation nearby [confirm]");
            return;
        }
        boolean confirm = args.length >= 5 && args[4].equalsIgnoreCase("confirm");
        HeartFoundationReport report = townHeartReconciliationService.inspectOrCleanupFoundation(player.getLocation(), confirm, player.getUniqueId());
        if (!report.found()) {
            sender.sendMessage("§e[HexTowns] " + report.message());
            return;
        }
        sender.sendMessage((report.removed() ? "§a" : report.activeTownProtected() ? "§c" : "§e")
                + "[HexTowns] Foundation world=" + report.world() + " center=" + report.centerX() + "," + report.y() + "," + report.centerZ()
                + " blocks=" + report.matchingBlocks() + " - " + report.message());
    }

    private void sendHeartReport(CommandSender sender, HeartReconciliationReport report) {
        sender.sendMessage("§6[HexTowns] Heart visual scan: chunks=" + report.chunksScanned()
                + " entities=" + report.heartEntities()
                + " valid=" + report.validGroups()
                + " orphan=" + report.orphanGroups()
                + " duplicate=" + report.duplicateGroups()
                + " malformed=" + report.malformedGroups()
                + " orphanRemoved=" + report.orphanEntitiesRemoved()
                + " duplicateRemoved=" + report.duplicateEntitiesRemoved()
                + " removed=" + report.removedEntities());
        int shown = 0;
        for (HeartVisualGroup group : report.groups()) {
            if (group.status() == hex.towns.heart.HeartVisualStatus.VALID) continue;
            if (shown++ >= 20) {
                sender.sendMessage("§7... pozostale wpisy pominieto (limit 20). ");
                break;
            }
            String town = group.townId() == null ? (group.rawTownId() == null ? "?" : group.rawTownId()) : group.townId().toString();
            sender.sendMessage("§7- §e" + group.status() + " §7town=" + town
                    + " world=" + group.world()
                    + " xyz=" + Math.round(group.x()) + "," + Math.round(group.y()) + "," + Math.round(group.z())
                    + " entities=" + group.entityCount()
                    + (group.removedEntities() > 0 ? " removed=" + group.removedEntities() : "")
                    + " reason=" + group.reason());
        }
    }

    private void sendHeartAdminUsage(CommandSender sender) {
        sender.sendMessage("§e/townadmin heart scan nearby [radius]");
        sender.sendMessage("§e/townadmin heart scan loaded");
        sender.sendMessage("§e/townadmin heart purge-orphans nearby [radius] [--dry-run]");
        sender.sendMessage("§e/townadmin heart purge-orphans loaded [--dry-run]");
        sender.sendMessage("§e/townadmin heart purge-visual <townUuid> [--dry-run]");
        sender.sendMessage("§e/townadmin heart rerender <townUuid|name>");
        sender.sendMessage("§e/townadmin heart cleanup-foundation nearby [confirm]");
    }

    private boolean hasArg(String[] args, String wanted) {
        for (String arg : args) if (arg.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    private double parseHeartRadius(String raw, double fallback) {
        try {
            return Math.max(1.0D, Math.min(256.0D, Double.parseDouble(raw)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }


    private void handleAdminFind(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.lookup")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.lookup.");
            return;
        }
        if (args.length < 3 || args[2].isBlank()) {
            sender.sendMessage("§eUzycie: /town admin find <nazwa>");
            return;
        }
        String query = joinArgs(args, 2);
        List<Town> matches = townRefResolver.search(query, 20);
        if (matches.isEmpty()) {
            sender.sendMessage("§eNie znaleziono aktywnego miasta pasujacego do: " + query);
            return;
        }
        sender.sendMessage("§6[HexTowns] Znaleziono " + matches.size() + (matches.size() >= 20 ? "+" : "") + " miast:");
        for (Town town : matches) sendTownCandidate(sender, town);
        sender.sendMessage("§7Teleport: §f/town admin tp #<ID>");
    }

    private void handleAdminTp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("hextowns.admin.tp")) {
            sender.sendMessage("§cBrak uprawnienia hextowns.admin.tp.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cTeleport administracyjny wymaga gracza.");
            return;
        }
        if (args.length < 3 || args[2].isBlank()) {
            sender.sendMessage("§eUzycie: /town admin tp <nazwa|#ID|ID|UUID>");
            return;
        }

        String townRef = joinArgs(args, 2);
        Town town = resolveAdminTown(sender, townRef);
        if (town == null) return;

        TownHeartLocation heart = townHeartService.heartOf(town.id()).orElse(null);
        if (heart == null) {
            sender.sendMessage("§cMiasto #" + town.internalId() + " " + town.name() + " istnieje, ale nie ma aktywnego rekordu serca.");
            sender.sendMessage("§7UUID: " + town.id() + " | world: " + town.world());
            return;
        }
        World world = Bukkit.getWorld(heart.world());
        if (world == null) {
            sender.sendMessage("§cNie mozna teleportowac: swiat miasta '" + heart.world() + "' nie jest zaladowany.");
            return;
        }
        world.getChunkAt(heart.chunkX(), heart.chunkZ()).load(true);
        Location destination = safeHeartTeleportLocation(world, heart, player);
        if (destination == null) {
            sender.sendMessage("§cNie znaleziono bezpiecznej pozycji teleportu przy sercu miasta #" + town.internalId() + ".");
            return;
        }

        if (!player.teleport(destination)) {
            sender.sendMessage("§cTeleport do miasta nie powiodl sie.");
            return;
        }

        service.audit(town.id(), player.getUniqueId(), "TOWN_ADMIN_TP",
                "internalId=" + town.internalId()
                        + ",town=" + town.name()
                        + ",world=" + heart.world()
                        + ",heart=" + heart.x() + "," + heart.y() + "," + heart.z());
        sender.sendMessage("§aTeleportowano do serca miasta §f" + town.name() + " §7(#" + town.internalId() + ").");
    }

    private Town resolveAdminTown(CommandSender sender, String token) {
        TownRefResolver.Resolution resolution = townRefResolver.resolve(token);
        if (resolution.found()) return resolution.town();
        if (resolution.status() == TownRefResolver.Status.AMBIGUOUS) {
            sender.sendMessage("§eNazwa '" + token + "' nie jest unikalna. Znaleziono " + resolution.candidates().size() + " miasta:");
            for (Town candidate : resolution.candidates()) sendTownCandidate(sender, candidate);
            sender.sendMessage("§eUzyj np. §f/town admin tp #" + resolution.candidates().get(0).internalId());
            return null;
        }
        sender.sendMessage("§cNie znaleziono aktywnego miasta: " + token);
        return null;
    }

    private void sendTownCandidate(CommandSender sender, Town town) {
        sender.sendMessage("§7#" + town.internalId()
                + " §f" + town.name()
                + " §8| §7UUID " + town.id()
                + " §8| §7world " + town.world()
                + " §8| §7owner " + town.ownerId());
    }

    private Location safeHeartTeleportLocation(World world, TownHeartLocation heart, Player player) {
        int[][] offsets = {
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {2, 2}, {2, -2}, {-2, 2}, {-2, -2},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {0, 0}
        };
        int baseFeetY = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, heart.y() - 1));
        int[] yOffsets = {0, 1, 2, -1};
        for (int yOffset : yOffsets) {
            int feetY = baseFeetY + yOffset;
            if (feetY <= world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) continue;
            for (int[] offset : offsets) {
                int x = heart.x() + offset[0];
                int z = heart.z() + offset[1];
                var below = world.getBlockAt(x, feetY - 1, z);
                var feet = world.getBlockAt(x, feetY, z);
                var head = world.getBlockAt(x, feetY + 1, z);
                if (!below.getType().isSolid() || !feet.isPassable() || !head.isPassable()) continue;
                return new Location(world, x + 0.5D, feetY, z + 0.5D, player.getLocation().getYaw(), player.getLocation().getPitch());
            }
        }
        return null;
    }

    private void handleAdminCleanup(CommandSender sender, String[] args) {
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "status";
        if (action.equals("status")) {
            service.cleanupJobSummaries(20).thenCombine(service.pendingPlayerResetCount(), (jobs, resets) -> new Object[]{jobs, resets})
                    .whenComplete((data, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            sender.sendMessage("§c[HexTowns] Cleanup status failed: " + unwrapCompletion(error).getMessage());
                            return;
                        }
                        @SuppressWarnings("unchecked")
                        List<hex.towns.database.TownRepository.CleanupJobSummary> jobs = (List<hex.towns.database.TownRepository.CleanupJobSummary>) data[0];
                        int resets = (Integer) data[1];
                        sender.sendMessage("§6[HexTowns] cleanup jobs=" + jobs.size() + " pendingPlayerResets=" + resets);
                        for (var job : jobs) {
                            sender.sendMessage("§7- " + job.townUuid() + " state=" + job.state() + " retries=" + job.retryCount()
                                    + (job.lastError() == null ? "" : " error=" + job.lastError()));
                        }
                    }));
            return;
        }
        if (action.equals("pending-players")) {
            service.pendingPlayerResetCount().whenComplete((count, error) -> Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(error == null ? "§6[HexTowns] pending player resets=" + count
                            : "§c[HexTowns] Pending-reset query failed: " + unwrapCompletion(error).getMessage())));
            return;
        }
        if (action.equals("scan-orphans")) {
            sender.sendMessage("§6[HexTowns] Running read-only orphan scan...");
            service.scanOrphans().whenComplete((report, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    sender.sendMessage("§c[HexTowns] Orphan scan failed: " + unwrapCompletion(error).getMessage());
                    return;
                }
                sender.sendMessage("§6[HexTowns] Orphan scan; active table prefix='" + report.activePrefix() + "'");
                report.counts().forEach((key, count) -> sender.sendMessage((count == 0 ? "§7" : "§e") + "- " + key + " = " + count));
                for (String warning : report.warnings()) sender.sendMessage("§c! " + warning);
                sender.sendMessage("§7Read-only scan complete; no rows were modified.");
            }));
            return;
        }
        if (action.equals("repair-orphans")) {
            boolean apply = hasArg(args, "--apply");
            if (!apply && !hasArg(args, "--dry-run")) {
                sender.sendMessage("§7[HexTowns] No mode specified; defaulting to --dry-run.");
            }
            sender.sendMessage(apply
                    ? "§e[HexTowns] Applying safe orphan repairs..."
                    : "§6[HexTowns] Dry-running safe orphan repairs...");
            service.repairSafeOrphans(apply).whenComplete((report, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    sender.sendMessage("§c[HexTowns] Orphan repair failed: " + unwrapCompletion(error).getMessage());
                    return;
                }
                report.affected().forEach((key, count) -> sender.sendMessage((count == 0 ? "§7" : "§e") + "- " + key + " = " + count));
                sender.sendMessage(report.applied()
                        ? "§a[HexTowns] Safe orphan repair applied. Run scan-orphans again to verify remaining/ambiguous rows."
                        : "§7[HexTowns] Dry-run only; no rows were modified. Use --apply after DB backup.");
            }));
            return;
        }
        if (action.equals("resume")) {
            service.resumeCleanupRecovery();
            sender.sendMessage("§6[HexTowns] Cleanup recovery/resume scheduled.");
            return;
        }
        if (action.equals("namespaces")) {
            service.cleanupNamespaces().whenComplete((rows, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    sender.sendMessage("§c[HexTowns] Namespace query failed: " + unwrapCompletion(error).getMessage());
                    return;
                }
                sender.sendMessage("§6[HexTowns] Cleanup namespace registry:");
                for (var row : rows) {
                    sender.sendMessage((row.active() ? "§a" : "§7") + "- " + row.namespace() + " active=" + row.active() +
                            " plugin=" + row.pluginName() + (row.pluginVersion() == null ? "" : " v" + row.pluginVersion()));
                }
            }));
            return;
        }
        if (action.equals("namespace")) {
            if (args.length < 5 || !(args[3].equalsIgnoreCase("retire") || args[3].equalsIgnoreCase("activate"))) {
                sender.sendMessage("§eUsage: /townadmin cleanup namespace <retire|activate> <namespace>");
                return;
            }
            boolean active = args[3].equalsIgnoreCase("activate");
            String namespace = args[4];
            service.setCleanupNamespaceActive(namespace, active).whenComplete((changed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) sender.sendMessage("§c[HexTowns] Namespace update failed: " + unwrapCompletion(error).getMessage());
                else if (!Boolean.TRUE.equals(changed)) sender.sendMessage("§e[HexTowns] Unknown namespace: " + namespace);
                else sender.sendMessage("§6[HexTowns] Namespace " + namespace + " is now " + (active ? "ACTIVE" : "RETIRED") + ".");
            }));
            return;
        }
        if (action.equals("retry") || action.equals("cables")) {
            if (args.length < 4) {
                sender.sendMessage("§eUsage: /townadmin cleanup " + action + " <townUuid>");
                return;
            }
            final UUID townUuid;
            try { townUuid = UUID.fromString(args[3]); }
            catch (IllegalArgumentException ex) { sender.sendMessage("§cInvalid town UUID."); return; }
            java.util.concurrent.CompletableFuture<Boolean> retry = action.equals("cables")
                    ? service.retryCleanupNamespace(townUuid, "cables")
                    : service.retryCleanup(townUuid);
            retry.whenComplete((done, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) sender.sendMessage("§c[HexTowns] Retry failed: " + unwrapCompletion(error).getMessage());
                else if (action.equals("cables")) sender.sendMessage(Boolean.TRUE.equals(done)
                        ? "§6[HexTowns] Cable cleanup completed for " + townUuid
                        : "§e[HexTowns] Cable cleanup unavailable or still failed for " + townUuid);
                else sender.sendMessage("§6[HexTowns] Retry " + (Boolean.TRUE.equals(done) ? "completed" : "left retryable parts") + " for " + townUuid);
            }));
            return;
        }
        sender.sendMessage("§e/townadmin cleanup <status|retry <uuid>|resume|scan-orphans|repair-orphans [--dry-run|--apply]|namespaces|namespace <retire|activate> <ns>|cables <uuid>|pending-players>");
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
        Town town = resolveAdminTown(sender, args[2]);
        if (town == null) return;
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
        if (command.getName().equalsIgnoreCase("townadmin")) {
            if (!sender.hasPermission("hextowns.admin")) return List.of();
            if (args.length == 1) return filter(List.of("find", "tp", "cleanup", "coop", "metrics", "list", "reload", "addgrowth", "giveheart", "syncgrowth", "heart"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("cleanup")) return filter(List.of("status", "retry", "resume", "scan-orphans", "repair-orphans", "namespaces", "namespace", "cables", "pending-players"), args[1]);
            if (args.length == 2 && args[0].equalsIgnoreCase("coop")) return filter(List.of("debug"), args[1]);
            if (args.length == 2 && args[0].equalsIgnoreCase("heart")) return filter(List.of("scan", "purge-orphans", "purge-visual", "rerender", "cleanup-foundation"), args[1]);
            if (args.length == 3 && args[0].equalsIgnoreCase("cleanup") && args[1].equalsIgnoreCase("namespace")) return filter(List.of("retire", "activate"), args[2]);
            if (args.length == 3 && args[0].equalsIgnoreCase("cleanup") && args[1].equalsIgnoreCase("repair-orphans")) return filter(List.of("--dry-run", "--apply"), args[2]);
            if (args.length == 3 && args[0].equalsIgnoreCase("heart") && args[1].equalsIgnoreCase("scan")) return filter(List.of("nearby", "loaded"), args[2]);
            if (args.length == 3 && args[0].equalsIgnoreCase("heart") && args[1].equalsIgnoreCase("purge-orphans")) return filter(List.of("nearby", "loaded"), args[2]);
            if (args.length == 3 && args[0].equalsIgnoreCase("heart") && args[1].equalsIgnoreCase("cleanup-foundation")) return filter(List.of("nearby"), args[2]);
            return List.of();
        }
        if (args.length == 1) {
            List<String> commands = new ArrayList<>(List.of("menu", "guide", "manage", "claims", "collections", "minions", "danger", "create", "claim", "accept", "destroy", "rename", "check", "info", "here", "map", "leave"));
            if (sender.hasPermission("hextowns.admin")) { commands.add("admin"); commands.add("reload"); }
            return filter(commands, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("find", "tp", "metrics", "list", "reload", "cleanup", "coop", "addgrowth", "growthadd", "giveheart", "syncgrowth", "growthsync", "heart"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("coop") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("debug"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("cleanup") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("status", "retry", "resume", "scan-orphans", "repair-orphans", "namespaces", "namespace", "cables", "pending-players"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("cleanup") && args[2].equalsIgnoreCase("namespace") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("retire", "activate"), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("cleanup") && args[2].equalsIgnoreCase("repair-orphans") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("--dry-run", "--apply"), args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("heart") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("scan", "purge-orphans", "purge-visual", "rerender", "cleanup-foundation"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("heart") && args[2].equalsIgnoreCase("scan") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("nearby", "loaded"), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("heart") && args[2].equalsIgnoreCase("purge-orphans") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("nearby", "loaded"), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("heart") && args[2].equalsIgnoreCase("cleanup-foundation") && sender.hasPermission("hextowns.admin")) {
            return filter(List.of("nearby"), args[3]);
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


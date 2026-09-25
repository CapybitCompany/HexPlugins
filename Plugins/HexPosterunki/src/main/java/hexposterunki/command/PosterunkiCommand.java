package hexposterunki.command;

import hex.core.api.ui.UiTokens;
import hexposterunki.HexPosterunkiPlugin;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.persistence.AuditEvent;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Diagnostic and recovery tooling. Normal operation is fully automatic; these commands exist to
 * inspect, validate and unstick the system, never to drive it.
 *
 * <p>{@code rewards} is the narrow clarification path for entitlements whose delivery outcome could
 * not be established after a crash. It is gated behind its own permission because resolving such a
 * claim is a judgement call, not a routine action.
 */
public final class PosterunkiCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "hexposterunki.admin";
    private static final String REWARD_PERMISSION = "hexposterunki.admin.rewards";
    private static final List<String> SUB_COMMANDS =
            List.of("status", "validate", "start", "stop", "reset", "reload", "rewards");

    private final HexPosterunkiPlugin plugin;
    private final OutpostEngine engine;
    private final PosterunkiUi ui;

    public PosterunkiCommand(HexPosterunkiPlugin plugin, OutpostEngine engine, PosterunkiUi ui) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            ui.send(sender, UiKeys.ADMIN_NO_PERMISSION, new UiTokens());
            return true;
        }
        if (args.length == 0) {
            ui.send(sender, UiKeys.ADMIN_USAGE, UiTokens.of("usage", String.join("|", SUB_COMMANDS)));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "validate" -> validate(sender);
            case "start" -> start(sender, args.length > 1 ? args[1] : null);
            case "stop" -> stop(sender);
            case "reset" -> reset(sender);
            case "reload" -> reload(sender);
            case "rewards" -> rewards(sender, args);
            default -> ui.send(sender, UiKeys.ADMIN_USAGE, UiTokens.of("usage", String.join("|", SUB_COMMANDS)));
        }
        return true;
    }

    private void status(CommandSender sender) {
        ui.send(sender, UiKeys.ADMIN_STATUS_HEADER, new UiTokens());
        line(sender, "Stan uruchomienia", plugin.runtimeState().polishLabel());
        line(sender, "Silnik", engine.operational()
                ? "aktywny" : "wstrzymany (" + engine.stoppedReason() + ")");
        line(sender, "Tryb pracy", engine.mode().polishLabel());
        line(sender, "Faza", engine.phase().polishLabel());
        if (engine.phase() == RunPhase.FAILED && !engine.state().failureReason().isBlank()) {
            line(sender, "Powód błędu", engine.state().failureReason());
        }
        line(sender, "Posterunek", engine.activeOutpost()
                .map(OutpostDefinition::displayName).orElse("—"));
        line(sender, "Identyfikator runu", engine.state().runId().orElse("—"));
        line(sender, "Fala", engine.state().wave() + "/" + plugin.config().waveCount());
        line(sender, "Pozostało mobów", String.valueOf(engine.liveMobs().size()));
        line(sender, "Kontrolująca drużyna", engine.state().controllingTownName().isBlank()
                ? "—" : engine.state().controllingTownName());
        line(sender, "Boss", engine.state().bossId().orElse("brak")
                + " (" + engine.state().bossStatus().polishLabel()
                + ", prób przywołania: " + engine.state().bossSpawnAttempts() + ")");
        if (engine.phase() == RunPhase.LOOTING) {
            long secondsLeft = Math.max(0L,
                    (engine.state().lootUntilMillis() - System.currentTimeMillis()) / 1000L);
            line(sender, "Czas na łupy", secondsLeft + " s");
        }
        line(sender, "Rewizja stanu", String.valueOf(engine.state().revision()));
        line(sender, "Zapis stanu", plugin.persistence().healthy()
                ? "sprawny" : "BŁĄD: " + plugin.persistence().lastError());
        int pendingSideWrites = plugin.persistence().pendingSideWrites();
        String sideWriteError = plugin.persistence().lastSideWriteError();
        line(sender, "Zapisy pomocnicze", pendingSideWrites == 0 ? "brak zaległości"
                : pendingSideWrites + " oczekuje na zapis (stan bloków / statusy nagród)"
                + (sideWriteError == null ? ", ponawiane" : ", ostatni błąd: " + sideWriteError));
        line(sender, "Reset w toku", engine.resetInProgress() ? "tak" : "nie");
        line(sender, "Graczy w regionie", String.valueOf(engine.participation().insideIds().size()));
        line(sender, "HexTowns", plugin.townsAdapter().status());
        line(sender, "STORMBOSSY", plugin.bossAdapterStatus());
        line(sender, "HexCustomMobs", plugin.customMobsStatus());
        line(sender, "Baza danych", plugin.databaseStatus());
        line(sender, "Poprawnych posterunków", String.valueOf(plugin.catalog().all().size()));
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "status");
    }

    private void line(CommandSender sender, String label, String value) {
        ui.send(sender, UiKeys.ADMIN_STATUS_LINE, UiTokens.of("label", label).put("value", value));
    }

    private void validate(CommandSender sender) {
        OutpostCatalog catalog = plugin.catalog();
        PosterunkiConfig config = plugin.config();
        int problems = 0;

        for (Map.Entry<String, String> skipped : catalog.report().skipped().entrySet()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM, UiTokens.of("what", "posterunek " + skipped.getKey())
                    .put("reason", skipped.getValue()));
        }
        for (String warning : catalog.report().warnings()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM,
                    UiTokens.of("what", "konfiguracja").put("reason", warning));
        }
        for (String problem : plugin.validateWaves()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM, UiTokens.of("what", "fale").put("reason", problem));
        }
        for (String problem : plugin.validateBosses()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM, UiTokens.of("what", "boss").put("reason", problem));
        }
        if (!plugin.townsAdapter().available()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM,
                    UiTokens.of("what", "HexTowns").put("reason", plugin.townsAdapter().status()));
        }
        if (!plugin.persistence().available()) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM,
                    UiTokens.of("what", "baza danych").put("reason", plugin.databaseStatus()));
        }
        if (config.waveCount() == 0) {
            problems++;
            ui.send(sender, UiKeys.ADMIN_VALIDATE_PROBLEM,
                    UiTokens.of("what", "fale").put("reason", "brak zdefiniowanych fal w config.yml"));
        }
        if (problems == 0) {
            ui.send(sender, UiKeys.ADMIN_VALIDATE_OK,
                    UiTokens.of("count", String.valueOf(catalog.all().size())));
        }
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "validate problems=" + problems);
    }

    private void start(CommandSender sender, String outpostId) {
        if (!engine.operational()) {
            ui.send(sender, UiKeys.ADMIN_ERROR,
                    UiTokens.of("reason", "silnik wstrzymany: " + engine.stoppedReason()));
            return;
        }
        if (!engine.mode().allowsProgress()) {
            ui.send(sender, UiKeys.ADMIN_ERROR,
                    UiTokens.of("reason", "nie można teraz uruchomić posterunku: " + engine.mode().polishLabel()));
            return;
        }
        if (engine.phase() != RunPhase.COOLDOWN) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason",
                    "posterunek już działa (faza " + engine.phase().polishLabel() + "). Użyj /posterunki stop."));
            return;
        }
        if (outpostId != null && plugin.catalog().find(outpostId.toLowerCase(Locale.ROOT)).isEmpty()) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", "nieznany posterunek '" + outpostId + "'"));
            return;
        }
        boolean started = engine.beginNewRun(outpostId == null ? null : outpostId.toLowerCase(Locale.ROOT),
                System.currentTimeMillis(), sender.getName());
        if (!started) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", "brak dostępnego posterunku"));
            return;
        }
        Optional<OutpostDefinition> active = engine.activeOutpost();
        ui.send(sender, UiKeys.ADMIN_STARTED,
                UiTokens.of("name", active.map(OutpostDefinition::displayName).orElse("?")));
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "start " + engine.state().outpostId().orElse("-"));
    }

    private void stop(CommandSender sender) {
        if (engine.phase() == RunPhase.COOLDOWN) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", "żaden posterunek nie jest aktywny"));
            return;
        }
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "stop");
        String problem = plugin.adminStop();
        if (problem != null) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", problem));
            return;
        }
        ui.send(sender, UiKeys.ADMIN_STOPPED, new UiTokens());
    }

    private void reset(CommandSender sender) {
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "reset");
        String problem = plugin.adminReset();
        if (problem != null) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", problem));
            return;
        }
        ui.send(sender, UiKeys.ADMIN_RESET, new UiTokens());
    }

    private void reload(CommandSender sender) {
        engine.audit(AuditEvent.ADMIN_ACTION, sender.getName(), "reload");
        String problem = plugin.reloadRuntime();
        if (problem != null) {
            ui.send(sender, UiKeys.ADMIN_ERROR, UiTokens.of("reason", problem));
            return;
        }
        ui.send(sender, UiKeys.ADMIN_RELOADED, new UiTokens());
    }

    // ---------------------------------------------------------------- rewards

    private void rewards(CommandSender sender, String[] args) {
        if (!sender.hasPermission(REWARD_PERMISSION)) {
            ui.send(sender, UiKeys.ADMIN_NO_PERMISSION, new UiTokens());
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> listClaims(sender);
            case "retry" -> resolveClaim(sender, args, RewardClaim.Status.CLAIMED);
            case "resolve" -> resolveClaim(sender, args, RewardClaim.Status.DELIVERED);
            default -> ui.send(sender, UiKeys.ADMIN_USAGE,
                    UiTokens.of("usage", "rewards <list|retry <id>|resolve <id>>"));
        }
    }

    private void listClaims(CommandSender sender) {
        plugin.persistence().loadClaims(RewardClaim.Status.NEEDS_REVIEW)
                .thenAccept(claims -> Bukkit.getScheduler().runTask(plugin, () -> {
                    ui.send(sender, UiKeys.ADMIN_REWARD_HEADER, new UiTokens());
                    if (claims.isEmpty()) {
                        ui.send(sender, UiKeys.ADMIN_REWARD_EMPTY, new UiTokens());
                        return;
                    }
                    for (RewardClaim claim : claims) {
                        ui.send(sender, UiKeys.ADMIN_REWARD_LINE, UiTokens.of("id", claim.key())
                                .put("place", String.valueOf(claim.place()))
                                .put("status", claim.status().polishLabel())
                                .put("attempts", String.valueOf(claim.attempts()))
                                .put("progress", String.valueOf(claim.progress()))
                                .put("problem", claim.lastError() == null ? "" : claim.lastError()));
                    }
                }));
    }

    private void resolveClaim(CommandSender sender, String[] args, RewardClaim.Status target) {
        if (args.length < 3) {
            ui.send(sender, UiKeys.ADMIN_USAGE,
                    UiTokens.of("usage", "rewards " + (target == RewardClaim.Status.CLAIMED ? "retry" : "resolve")
                            + " <id>"));
            return;
        }
        String key = args[2];
        plugin.persistence().loadClaims(RewardClaim.Status.NEEDS_REVIEW)
                .thenAccept(claims -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Optional<RewardClaim> claim = claims.stream()
                            .filter(candidate -> candidate.key().equals(key)).findFirst();
                    if (claim.isEmpty()) {
                        ui.send(sender, UiKeys.ADMIN_REWARD_UNKNOWN, UiTokens.of("id", key));
                        return;
                    }
                    plugin.persistence().finishDelivery(key, target, claim.get().progress(),
                            "decyzja administratora: " + sender.getName());
                    plugin.persistence().audit(claim.get().runId(), claim.get().outpostId(),
                            AuditEvent.REWARD_RESOLVED, sender.getName(),
                            "id=" + key + " -> " + target.name());
                    if (target == RewardClaim.Status.CLAIMED) {
                        ui.send(sender, UiKeys.ADMIN_REWARD_RETRY, UiTokens.of("id", key));
                        plugin.rewards().deliverOutstanding();
                    } else {
                        ui.send(sender, UiKeys.ADMIN_REWARD_RESOLVED, UiTokens.of("id", key));
                    }
                }));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            List<String> ids = new ArrayList<>(plugin.catalog().outposts().keySet());
            return ids.stream().filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rewards")) {
            return List.of("list", "retry", "resolve").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}

package hex.minigames.runtime;

import hex.events.api.EventExecutionContext;
import hex.events.api.EventFailure;
import hex.events.api.EventJoinRequest;
import hex.events.api.EventJoinResult;
import hex.events.api.EventOutcome;
import hex.events.api.EventResult;
import hex.events.api.EventStopReason;
import hex.events.api.LeaveReason;
import hex.events.api.ResultSubject;
import hex.events.api.ResultSubjectType;
import hex.events.api.StartResult;
import hex.minigames.config.ConfiguredSound;
import hex.minigames.config.LoadedMinigamesConfig;
import hex.minigames.config.PregameConfig;
import hex.minigames.event.HexEventsBridge;
import hex.minigames.game.DebugMinigame;
import hex.minigames.game.EventDecision;
import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameAvailability;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.PlayerRoundResult;
import hex.minigames.game.RoundContext;
import hex.minigames.game.RoundEndReason;
import hex.minigames.game.RoundResult;
import hex.minigames.game.common.BossBarDisplay;
import hex.minigames.game.common.BossBarSettings;
import hex.minigames.game.dalgona.DalgonaConfig;
import hex.minigames.model.LocationSpec;
import hex.minigames.persistence.PlayerSnapshotRepository;
import hex.minigames.persistence.StoredPlayerState;
import hex.minigames.score.ScoreService;
import hex.minigames.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class MinigamesSessionService {
    private final Plugin plugin;
    private final PlayerSnapshotRepository snapshots;
    private final ScoreService scores;
    private final hex.minigames.game.MinigameRegistry registry;
    private final GameSelectionService selector = new GameSelectionService();
    private final Map<UUID, Set<UUID>> pendingParticipants = new HashMap<>();
    private final Set<UUID> restoring = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, Object> pendingRoundRespawns = new HashMap<>();
    private final Set<UUID> chatBlocked = ConcurrentHashMap.newKeySet();
    private final BossBarDisplay pregameBossBars = new BossBarDisplay();
    private LoadedMinigamesConfig config;
    private HexEventsBridge eventsBridge;
    private MinigamesSession activeSession;
    private BukkitTask tickTask;
    private SeriesCelebration celebration;
    public void celebration(SeriesCelebration celebration) { this.celebration = celebration; }
    /** A reset is queued behind all earlier database writes and refreshes the placeholder cache. */
    public void resetGlobalScores(org.bukkit.command.CommandSender sender) {
        if (activeSession != null) { sender.sendMessage(config.messages().get("score-reset-running", "&cZakoncz aktywna serie przed resetem punktow.")); return; }
        scores.resetAll().whenComplete((unused, error) -> Bukkit.getScheduler().runTask(plugin, () ->
                sender.sendMessage(config.messages().get(error == null ? "score-reset-success" : "score-reset-failed",
                        error == null ? "&aWyzerowano punkty wszystkich graczy." : "&cReset punktow nie powiodl sie; sprawdz konsole."))));
    }

    public MinigamesSessionService(
            Plugin plugin,
            PlayerSnapshotRepository snapshots,
            ScoreService scores,
            hex.minigames.game.MinigameRegistry registry
    ) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        this.scores = scores;
        this.registry = registry;
    }

    public void configure(LoadedMinigamesConfig config) {
        this.config = config;
        registry.rebuild(config.gameDefinitions());
        for (String error : config.errors()) {
            plugin.getLogger().warning(error);
        }
        for (String error : config.global().errors()) {
            plugin.getLogger().warning(error);
        }
        if (plugin != null) {
            registry.definition(DalgonaConfig.ID).ifPresent(definition -> {
                List<String> errors = new ArrayList<>();
                DalgonaConfig loaded = DalgonaConfig.fromDefinition(definition, errors);
                plugin.getLogger().info("Dalgona patterns loaded: " + loaded.patterns().size());
            });
        }
    }

    public void eventsBridge(HexEventsBridge eventsBridge) {
        this.eventsBridge = eventsBridge;
    }

    public void startTicking() {
        stopTicking();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stopTicking() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public boolean available() {
        return config != null
                && config.valid()
                && snapshots.available()
                && scores.available()
                && world() != null;
    }

    public String availabilityReason() {
        if (config == null) return "Config not loaded";
        if (!config.valid()) return "Invalid config: " + String.join("; ", allConfigErrors());
        if (!snapshots.available()) return "Snapshot storage unavailable";
        if (!scores.available()) return "Score storage unavailable";
        if (world() == null) return "World not loaded: " + config.global().worldName();
        return "";
    }

    public String prepareFailure(EventExecutionContext context) {
        if (!available()) return availabilityReason();
        return null;
    }

    public EventJoinResult joinFromHexEvents(EventJoinRequest request) {
        if (!available()) return new EventJoinResult(EventJoinResult.Status.MODULE_UNAVAILABLE, availabilityReason());
        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) return EventJoinResult.denied("Gracz offline");
        if (activeSession != null && activeSession.instanceId().equals(request.instanceId())) {
            if (activeSession.forfeited(request.playerId())) {
                return EventJoinResult.denied("Utraciles udzial w tej serii HexMinigames.");
            }
            if (activeSession.contains(request.playerId())) {
                return EventJoinResult.alreadyJoined();
            }
            if (activeSession.activeParticipantCount() >= config.global().maxPlayers()) {
                return new EventJoinResult(EventJoinResult.Status.FULL, maxPlayersMessage());
            }
            if (activeSession.state() == SeriesState.PRE_GAME_WAITING) {
                return addPregameParticipant(request);
            }
            return EventJoinResult.denied("Seria HexMinigames juz trwa.");
        }
        if (activeSession != null && activeSession.contains(request.playerId())) {
            return EventJoinResult.denied("Jestes juz w aktywnej serii HexMinigames.");
        }
        try {
            if (snapshots.find(request.playerId()).isPresent()) {
                return EventJoinResult.denied(config.messages().raw("snapshot-existing", "Pending inventory restore."));
            }
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not check minigames snapshot before join: " + rootMessage(error));
            return EventJoinResult.error("Nie mozna sprawdzic snapshotu ekwipunku.");
        }
        if (hexEventsAdmissionWouldExceedMaxPlayers(request)) {
            return new EventJoinResult(EventJoinResult.Status.FULL, maxPlayersMessage());
        }
        pendingParticipants.computeIfAbsent(request.instanceId(), ignored -> new LinkedHashSet<>()).add(request.playerId());
        return EventJoinResult.joined();
    }

    public StartResult startEvent(EventExecutionContext context) {
        if (activeSession != null) return StartResult.failed(config.messages().raw("already-running", "Already running."));
        String failure = prepareFailure(context);
        if (failure != null) return StartResult.failed(failure);
        Set<UUID> participants = onlineOnly(mergedParticipants(context));
        if (participants.size() < Math.max(4, config.global().pregame().minimumPlayers()))
            return StartResult.failed(config.messages().raw("queue-too-few", "Potrzeba co najmniej {required} graczy.")
                    .replace("{required}", String.valueOf(Math.max(4, config.global().pregame().minimumPlayers()))));
        if (participants.size() > config.global().maxPlayers()) return StartResult.failed(maxPlayersMessage());
        String eligibleFailure = eligibleGamesFailure(participants.size(), config.global().gamesPerSeries());
        if (eligibleFailure != null) return StartResult.failed(eligibleFailure);
        String startFailure;
        if (config.global().pregame().enabled()) {
            startFailure = startSession(context.instanceId(), SessionMode.EVENT, context, participants, List.of(), true);
        } else {
            List<MinigameDefinition> selected = selector.select(
                    registry.eligible(participants.size(), false, false),
                    config.global().gamesPerSeries()
            );
            if (selected.size() < config.global().gamesPerSeries()) {
                return StartResult.failed("Not enough eligible minigames: " + selected.size() + "/" + config.global().gamesPerSeries());
            }
            startFailure = startSession(context.instanceId(), SessionMode.EVENT, context, participants, selected, false);
        }
        if (startFailure != null) return StartResult.failed(startFailure);
        pendingParticipants.remove(context.instanceId());
        return StartResult.started();
    }

    private EventJoinResult addPregameParticipant(EventJoinRequest request) {
        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) return EventJoinResult.denied("Gracz offline");
        return addPregameParticipant(player, request.instanceId());
    }

    private EventJoinResult addPregameParticipant(Player player, UUID instanceId) {
        if (activeSession == null || !activeSession.instanceId().equals(instanceId)) {
            return new EventJoinResult(EventJoinResult.Status.NOT_RUNNING, "Sesja HexMinigames nie jest aktywna.");
        }
        UUID playerId = player.getUniqueId();
        if (activeSession.forfeited(playerId)) {
            return EventJoinResult.denied("Gracz opuscil juz te sesje HexMinigames.");
        }
        if (activeSession.contains(playerId)) {
            return EventJoinResult.alreadyJoined();
        }
        if (activeSession.state() != SeriesState.PRE_GAME_WAITING) {
            return EventJoinResult.denied("Dolaczanie jest dostepne tylko przed rozpoczeciem losowania pre-game.");
        }
        if (activeSession.activeParticipantCount() >= config.global().maxPlayers()) {
            return new EventJoinResult(EventJoinResult.Status.FULL, maxPlayersMessage());
        }
        try {
            if (snapshots.find(playerId).isPresent()) {
                return EventJoinResult.denied(config.messages().raw("snapshot-existing", "Pending inventory restore."));
            }
            if (!snapshots.saveIfAbsent(player, instanceId)) {
                return EventJoinResult.denied(config.messages().raw("snapshot-existing", "Pending inventory restore."));
            }
        } catch (Throwable error) {
            plugin.getLogger().severe("Could not persist minigames snapshot before pregame join: " + rootMessage(error));
            return EventJoinResult.error(config.messages().raw("snapshot-save-failed", "Snapshot save failed."));
        }
        int globalAtStart = 0;
        try {
            globalAtStart = scores.getGlobalPoints(playerId);
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not read minigames global score for " + playerId + ": " + rootMessage(error));
        }
        activeSession.addParticipant(playerId, globalAtStart);
        activeSession.rememberName(playerId, player.getName());
        prepareMinigameState(player);
        teleportToPregame(player);
        showPregameBossBar(player);
        if (activeSession.canBeginPregame(requiredPregamePlayers(activeSession))) {
            beginPregameDraw(activeSession);
        }
        return EventJoinResult.joined();
    }

    public String startAdminSeries(Player initiator) {
        return startAdminSeries(initiator == null ? List.of() : List.of(initiator));
    }

    /** Starts the full five-game test flow for the explicitly selected online players. */
    public String startAdminSeries(List<Player> targets) {
        if (activeSession != null) return config.messages().raw("already-running", "Already running.");
        Set<UUID> participants = new LinkedHashSet<>();
        for (Player target : targets) {
            if (target == null || !target.isOnline()) return "&cWybrany gracz jest offline.";
            participants.add(target.getUniqueId());
        }
        if (participants.isEmpty()) return "&cPodaj co najmniej jednego gracza.";
        int playerCount = Math.max(1, participants.size());
        List<MinigameDefinition> eligible = registry.eligible(playerCount, false, false);
        if (eligible.size() < 5) {
            return config.messages().format("not-enough-games", "&cZa malo dostepnych minigier: {available}/{required}.", Map.of(
                    "available", String.valueOf(eligible.size()),
                    "required", "5"
            ));
        }
        String failure = startSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, participants, List.of(), true);
        return failure == null ? null : Text.color(config.messages().prefix() + failure);
    }

    public String startAdminSingle(String gameId, Player target) {
        return startAdminSingle(gameId, target == null ? List.of() : List.of(target));
    }

    public String startAdminSingle(String gameId, List<Player> targets) {
        if (activeSession != null) return config.messages().raw("already-running", "Already running.");
        Optional<MinigameDefinition> definition = definitionForAdmin(gameId);
        if (definition.isEmpty()) {
            return config.messages().format("unknown-game", "&cNieznana minigra: {game}", Map.of("game", gameId));
        }
        if (!definition.get().implemented()) {
            return config.messages().get("game-not-implemented", "&cMinigra nie jest jeszcze zaimplementowana.");
        }
        int playerCount = Math.max(1, targets == null ? 0 : targets.size());
        if (!definition.get().playerCountAllowed(playerCount)) {
            return config.messages().format("game-not-available", "&cMinigra jest niedostepna: {reason}", Map.of(
                    "reason", "Nieprawidlowa liczba graczy: " + playerCount
            ));
        }
        MinigameAvailability availability = registry.create(definition.get().id())
                .map(game -> game.availability(definition.get(), playerCount))
                .orElse(MinigameAvailability.unavailable("Factory missing"));
        if (!availability.available()) {
            return config.messages().format("game-not-available", "&cMinigra jest niedostepna: {reason}", Map.of("reason", availability.reason()));
        }
        Set<UUID> participants = new LinkedHashSet<>();
        if (targets != null) {
            for (Player target : targets) {
                if (target != null && target.isOnline()) participants.add(target.getUniqueId());
            }
        }
        if (participants.isEmpty()) return config.messages().raw("player-only", "&cTa komenda wymaga gracza.");
        String failure = startSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, participants, List.of(definition.get()), false);
        if (failure != null) return Text.color(config.messages().prefix() + failure);
        return config.messages().format("admin-test-started", "&aUruchomiono administracyjny test minigry: &f{game}", Map.of("game", definition.get().id()));
    }

    public String testJoin(Player target) {
        if (target == null || !target.isOnline()) return Text.color(config.messages().prefix() + "&cGracz offline.");
        if (!available()) {
            return config.messages().format("module-unavailable", "&cHexMinigames jest niedostepne: {reason}", Map.of(
                    "reason", availabilityReason()
            ));
        }
        if (!config.global().pregame().enabled()) {
            return config.messages().get("development-test-pregame-disabled", "&cTestjoin wymaga wlaczonego pre-game w config.yml.");
        }
        if (activeSession != null && activeSession.mode() == SessionMode.EVENT) {
            return config.messages().get("development-test-production-running", "&cNie mozna uzyc testjoin: trwa produkcyjna sesja HexEvents.");
        }
        if (activeSession != null && activeSession.mode() != SessionMode.DEVELOPMENT_TEST) {
            return config.messages().get("development-test-other-session-running", "&cInna sesja testowa HexMinigames juz trwa. Uzyj /hexminigames stop.");
        }

        boolean created = false;
        if (activeSession == null) {
            activeSession = new MinigamesSession(UUID.randomUUID(), SessionMode.DEVELOPMENT_TEST, null, Set.of(), List.of(), Map.of());
            transition(activeSession, SeriesState.PRE_GAME_WAITING, 0);
            plugin.getLogger().info("Started HexMinigames TEST/DEVELOPMENT session " + activeSession.instanceId());
            created = true;
        }

        UUID playerId = target.getUniqueId();
        if (activeSession.contains(playerId)) {
            return config.messages().format("development-test-duplicate", "&cGracz {player} jest juz uczestnikiem sesji TEST/DEVELOPMENT.", Map.of(
                    "player", target.getName()
            ));
        }
        if (activeSession.forfeited(playerId)) {
            return config.messages().format("development-test-forfeited", "&cGracz {player} opuscil juz te sesje TEST/DEVELOPMENT. Zatrzymaj ja i utworz nowa.", Map.of(
                    "player", target.getName()
            ));
        }
        if (activeSession.state() != SeriesState.PRE_GAME_WAITING) {
            return config.messages().get("development-test-not-accepting", "&cTestjoin jest dostepny tylko zanim rozpocznie sie losowanie pre-game.");
        }
        if (activeSession.activeParticipantCount() >= config.global().maxPlayers()) {
            return Text.color(config.messages().prefix() + maxPlayersMessage());
        }

        EventJoinResult result = addPregameParticipant(target, activeSession.instanceId());
        if (result.status() != EventJoinResult.Status.JOINED) {
            if (created && activeSession != null && activeSession.activeParticipantCount() == 0) activeSession = null;
            return Text.color(config.messages().prefix() + result.message());
        }
        return config.messages().format("development-test-joined", "&aDodano gracza {player} do sesji TEST/DEVELOPMENT. Uczestnicy: {players}/{required}.", Map.of(
                "player", target.getName(),
                "players", String.valueOf(activeSession.participants().size()),
                "required", String.valueOf(requiredPregamePlayers(activeSession))
        ));
    }

    public String testLeave(Player target) {
        if (target == null || !target.isOnline()) return Text.color(config.messages().prefix() + "&cGracz offline.");
        if (activeSession == null || activeSession.mode() != SessionMode.DEVELOPMENT_TEST) {
            return config.messages().get("development-test-not-running", "&cBrak aktywnej sesji TEST/DEVELOPMENT.");
        }
        UUID playerId = target.getUniqueId();
        if (!activeSession.contains(playerId)) {
            return config.messages().format("development-test-not-participant", "&cGracz {player} nie jest uczestnikiem sesji TEST/DEVELOPMENT.", Map.of(
                    "player", target.getName()
            ));
        }

        MinigamesSession session = activeSession;
        removeParticipant(session, playerId, RoundEndReason.SESSION_STOP, true);
        restoreOnline(target, true);
        if (activeSession == session && session.activeParticipantCount() == 0) {
            cancelSession(session, "NO_TEST_PARTICIPANTS", false);
        }

        if (activeSession == null) {
            return config.messages().format("development-test-left-stopped", "&eUsunieto gracza {player}; sesja TEST/DEVELOPMENT zostala zatrzymana.", Map.of(
                    "player", target.getName()
            ));
        }
        return config.messages().format("development-test-left", "&eUsunieto gracza {player} z sesji TEST/DEVELOPMENT. Uczestnicy: {players}/{required}.", Map.of(
                "player", target.getName(),
                "players", String.valueOf(activeSession.participants().size()),
                "required", String.valueOf(requiredPregamePlayers(activeSession))
        ));
    }

    public boolean developmentSessionActive() {
        return activeSession != null && activeSession.mode() == SessionMode.DEVELOPMENT_TEST;
    }

    public String selectDevelopmentGame(String gameId) {
        if (activeSession == null || activeSession.mode() != SessionMode.DEVELOPMENT_TEST) {
            return config.messages().raw("development-test-not-running", "&cBrak aktywnej sesji TEST/DEVELOPMENT.");
        }
        if (!isPregameState(activeSession.state())) {
            return config.messages().raw("development-test-game-select-too-late", "&cGre testowa mozna wybrac tylko podczas pre-game.");
        }
        Optional<MinigameDefinition> definition = registry.definition(gameId);
        if (definition.isEmpty()) {
            return config.messages().raw("unknown-game", "Unknown game.").replace("{game}", gameId);
        }
        MinigameDefinition game = definition.get();
        if (!game.implemented()) return config.messages().raw("game-not-implemented", "Minigra nie jest jeszcze zaimplementowana.");
        if (!game.playerCountAllowed(activeSession.activeParticipantCount())) {
            return config.messages().raw("game-not-available", "Minigra jest niedostepna: {reason}")
                    .replace("{reason}", "Nieprawidlowa liczba graczy dla " + game.id());
        }
        MinigameAvailability availability = registry.create(game.id())
                .map(created -> created.availability(game, activeSession.activeParticipantCount()))
                .orElse(MinigameAvailability.unavailable("Factory missing"));
        if (!availability.available()) {
            return config.messages().raw("game-not-available", "Minigra jest niedostepna: {reason}")
                    .replace("{reason}", availability.reason());
        }
        activeSession.replaceSelectedGames(List.of(game));
        if (activeSession.state() == SeriesState.PRE_GAME_WAITING
                && activeSession.canBeginPregame(requiredPregamePlayers(activeSession))) {
            beginPregameDraw(activeSession);
        }
        return config.messages().raw("development-test-game-selected", "&aWybrano gre TEST/DEVELOPMENT: &f{game}")
                .replace("{game}", game.id());
    }

    public String forceNextGame(String gameId) {
        if (activeSession == null) return config.messages().raw("not-running", "Not running.");
        if (activeSession.mode() != SessionMode.ADMIN_TEST) return "forcegame jest dostepne tylko dla sesji administracyjnej.";
        Optional<MinigameDefinition> definition = definitionForAdmin(gameId);
        if (definition.isEmpty()) return config.messages().raw("unknown-game", "Unknown game.").replace("{game}", gameId);
        if (!definition.get().implemented()) return config.messages().raw("game-not-implemented", "Minigra nie jest jeszcze zaimplementowana.");
        activeSession.forcedNextGame(definition.get());
        return config.messages().raw("forced-next-game", "Forced next game: {game}").replace("{game}", definition.get().id());
    }

    public void stopEvent(UUID instanceId, EventStopReason reason) {
        if (activeSession != null && activeSession.instanceId().equals(instanceId)) {
            cancelSession(activeSession, reason.name(), false);
            return;
        }
        markInstanceSnapshotsPending(instanceId);
    }

    public void leaveFromHexEvents(UUID instanceId, UUID playerId, LeaveReason reason) {
        Set<UUID> pending = pendingParticipants.get(instanceId);
        if (pending != null) pending.remove(playerId);
        if (activeSession == null || !activeSession.instanceId().equals(instanceId) || !activeSession.contains(playerId)) return;
        removeParticipant(activeSession, playerId, reason == LeaveReason.DISCONNECT ? RoundEndReason.PLAYER_QUIT : RoundEndReason.SESSION_STOP, true);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && reason != LeaveReason.DISCONNECT) restoreOnline(player, true);
        else markPending(playerId);
    }

    public void shutdown() {
        stopTicking();
        if (activeSession != null) {
            cancelSession(activeSession, "SERVER_SHUTDOWN", false);
        }
    }

    public void handlePendingJoin(Player player) {
        if (!snapshots.available()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || activeSessionContains(player.getUniqueId())) return;
            try {
                Optional<StoredPlayerState> snapshot = snapshots.find(player.getUniqueId());
                if (snapshot.isPresent()) restoreOnline(player, true);
            } catch (Throwable error) {
                plugin.getLogger().warning("Could not restore pending minigames snapshot for " + player.getName() + ": " + rootMessage(error));
            }
        }, 1L);
    }

    public void handleQuit(Player player) {
        if (activeSession == null || !activeSession.contains(player.getUniqueId())) return;
        MinigamesSession session = activeSession;
        removeParticipant(session, player.getUniqueId(), RoundEndReason.PLAYER_QUIT, true);
        restoreBeforeDisconnect(player);
    }

    public void handleChangedWorld(Player player) {
        if (activeSession == null || restoring.contains(player.getUniqueId()) || internalTeleports.contains(player.getUniqueId())) return;
        if (!activeSession.contains(player.getUniqueId())) return;
        if (isPregameState(activeSession.state())) {
            teleportToPregame(player);
            return;
        }
        if (player.getWorld().getName().equals(config.global().worldName())) return;
        MinigamesSession session = activeSession;
        removeParticipant(session, player.getUniqueId(), RoundEndReason.PLAYER_QUIT, true);
        restoreOnline(player, true);
    }

    public void handleMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event instanceof PlayerTeleportEvent || internalTeleports.contains(player.getUniqueId())
                || pendingRoundRespawns.containsKey(player.getUniqueId())) return;
        if (activeSession == null || !activeSession.contains(player.getUniqueId())) return;
        if (isPregameState(activeSession.state())) {
            keepInPregame(player, event.getTo());
            return;
        }
        RoundSession round = activeSession.currentRound();
        if (round == null) return;
        if (activeSession.state() == SeriesState.ROUND_RESULTS) {
            Location stay = event.getFrom().clone();
            if (event.getTo() != null) { stay.setYaw(event.getTo().getYaw()); stay.setPitch(event.getTo().getPitch()); }
            event.setTo(stay);
            return;
        }
        if (round.playerState(player.getUniqueId()) == RoundPlayerState.GHOST) {
            var restricted = round.minigame().ghostRegion(round.definition());
            if (restricted.isPresent() && event.getTo() != null) {
                var area = restricted.get();
                Location to = event.getTo().clone();
                to.setX(Math.clamp(to.getX(), area.minX() + 0.3, area.maxX() + 0.7));
                to.setY(Math.clamp(to.getY(), area.minY(), area.maxY() + 0.9));
                to.setZ(Math.clamp(to.getZ(), area.minZ() + 0.3, area.maxZ() + 0.7));
                event.setTo(to);
                return;
            }
            keepGhostInRegion(player, round);
            return;
        }
        RoundContext context = new RoundContext(this, round);
        Location before = player.getLocation();
        if (round.minigame().onMove(context, event) == EventDecision.DENY) {
            // A penalty may teleport the player. Cancellation must not send them back onto the arena.
            Location after = player.getLocation();
            if (!before.equals(after)) {
                event.setFrom(after);
                event.setTo(after);
            }
            event.setCancelled(true);
        }
    }

    public void handleChat(AsyncChatEvent event) {
        if (event == null || event.getPlayer() == null) return;
        if (chatBlocked.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Operators may use commands; /lobby still performs normal participant cleanup. */
    public void handleCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!activeSessionContains(player.getUniqueId())) return;
        if (player.isOp() && !isLobbyCommand(event.getMessage())) return;
        event.setCancelled(true);
        if (!isLobbyCommand(event.getMessage())) {
            player.sendMessage(Text.color("&cW minigrze możesz używać tylko /lobby."));
            return;
        }
        World lobby = Bukkit.getWorld("world");
        if (lobby == null) {
            player.sendMessage(Text.color("&cŚwiat world jest niedostępny."));
            return;
        }
        MinigamesSession session = activeSession;
        UUID playerId = player.getUniqueId();
        pendingRoundRespawns.remove(playerId);
        removeParticipant(session, playerId, RoundEndReason.SESSION_STOP, true);
        restoreOnline(player, true);
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setVelocity(new org.bukkit.util.Vector());
        teleportInternal(player, lobby.getSpawnLocation());
    }

    static boolean isLobbyCommand(String command) {
        return command != null && "/lobby".equalsIgnoreCase(command.trim());
    }

    public boolean handleTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (activeSession == null || !activeSession.contains(player.getUniqueId())) return false;
        if (!isPregameState(activeSession.state())) return false;
        if (restoring.contains(player.getUniqueId()) || internalTeleports.contains(player.getUniqueId())) return false;
        if (pregameContains(event.getTo())) return false;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> teleportToPregame(player));
        return true;
    }

    public boolean routeInteract(PlayerInteractEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onInteract(new RoundContext(this, round), event));
    }

    public void routeJump(com.destroystokyo.paper.event.player.PlayerJumpEvent event) {
        if (activeSession != null && (isPregameState(activeSession.state())
                || activeSession.state() == SeriesState.SERIES_RESULTS
                || activeSession.state() == SeriesState.INTERMISSION)) return;
        route(event.getPlayer(), event, round -> round.minigame().onJump(new RoundContext(this, round), event));
    }

    public void routeKnockback(io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (!(event.getEntity() instanceof Player player) || activeSession == null
                || !activeSession.contains(player.getUniqueId()) || activeSession.state() != SeriesState.ROUND_RUNNING) return;
        RoundSession round = activeSession.currentRound();
        if (round != null && round.playerState(player.getUniqueId()) == RoundPlayerState.ACTIVE) {
            round.minigame().onKnockback(new RoundContext(this, round), event);
        }
    }

    public boolean routeBlockBreak(BlockBreakEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onBlockBreak(new RoundContext(this, round), event));
    }

    public boolean routeBlockDamage(BlockDamageEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onBlockDamage(new RoundContext(this, round), event));
    }

    public boolean routeBlockPlace(BlockPlaceEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onBlockPlace(new RoundContext(this, round), event));
    }

    public boolean routeDamage(Player player, EntityDamageEvent event) {
        if (activeSessionContains(player.getUniqueId()) && activeSession.state() == SeriesState.ROUND_RUNNING) {
            RoundSession round = activeSession.currentRound();
            if (round != null && "hot_head".equals(round.definition().id())) {
                EventDecision decision = round.minigame().onDamage(new RoundContext(this, round), event);
                event.setCancelled(decision != EventDecision.ALLOW);
                return true;
            }
        }
        return route(player, event, round -> round.minigame().onDamage(new RoundContext(this, round), event));
    }

    public void routeEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (activeSession == null || activeSession.currentRound() == null) return;
        RoundSession round = activeSession.currentRound();
        round.minigame().onEntityExplode(new RoundContext(this, round), event);
    }

    public boolean routeRegainHealth(Player player, org.bukkit.event.entity.EntityRegainHealthEvent event) {
        if (!activeSessionContains(player.getUniqueId())) return false;
        RoundSession round = activeSession.currentRound();
        if (round == null) return false;
        if (round.minigame().onRegainHealth(new RoundContext(this, round), event) == EventDecision.DENY) {
            event.setCancelled(true);
        }
        return true;
    }

    public boolean routeDrop(PlayerDropItemEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onDropItem(new RoundContext(this, round), event));
    }

    public boolean routeInventoryClick(InventoryClickEvent event, Player player) {
        return route(player, event, round -> round.minigame().onInventoryClick(new RoundContext(this, round), event));
    }

    public boolean routeInventoryDrag(InventoryDragEvent event, Player player) {
        return route(player, event, round -> round.minigame().onInventoryDrag(new RoundContext(this, round), event));
    }

    public boolean routeToggleSneak(PlayerToggleSneakEvent event) {
        return route(event.getPlayer(), event, round -> round.minigame().onToggleSneak(new RoundContext(this, round), event));
    }

    public void requestRoundFinish(RoundSession round, RoundEndReason reason) {
        if (activeSession == null || activeSession.currentRound() != round) return;
        round.requestFinish(reason);
    }

    public void setRoundPlayerState(RoundSession round, UUID playerId, RoundPlayerState state) {
        if (activeSession == null || activeSession.currentRound() != round) return;
        round.playerState(playerId, state);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (state == RoundPlayerState.GHOST) applyGhost(player);
        if (state == RoundPlayerState.ACTIVE) applyActiveRoundState(player);
        if (state == RoundPlayerState.RESPAWN_DELAY) applyRespawnDelay(player);
        if (state == RoundPlayerState.FINISHED) announceOutcome(round, player, true, false);
        if (state == RoundPlayerState.GHOST || state == RoundPlayerState.ELIMINATED) announceOutcome(round, player, false, false);
    }

    private void announceOutcome(RoundSession round, Player player, boolean success, boolean refreshSubtitle) {
        boolean first = round.markOutcomeAnnounced(player.getUniqueId());
        if (first || refreshSubtitle) hex.minigames.game.common.RoundFeedback.show(player, success, first);
        if (first) {
            String message = Text.color(hex.minigames.game.common.RoundFeedback.announcement(player.getName(), success));
            for (Player viewer : onlinePlayers(activeSession)) viewer.sendMessage(message);
        }
    }

    public void blockChat(UUID playerId) {
        if (playerId != null) chatBlocked.add(playerId);
    }

    public void unblockChat(UUID playerId) {
        if (playerId != null) chatBlocked.remove(playerId);
    }

    boolean chatBlocked(UUID playerId) {
        return chatBlocked.contains(playerId);
    }

    public void forceEndRound() {
        if (activeSession == null || activeSession.currentRound() == null) return;
        finishRound(activeSession, activeSession.currentRound(), RoundEndReason.ADMIN_FORCE);
    }

    public String stopActiveFromAdmin() {
        if (activeSession == null) return config.messages().raw("not-running", "Not running.");
        cancelSession(activeSession, "ADMIN_STOP", true);
        return config.messages().raw("admin-stopped", "Stopped.");
    }

    /** Reload must restore Elytra's authored marker blocks before replacing its configuration. */
    public void restoreElytraBeforeReload() {
        if (activeSession != null && activeSession.currentRound() != null
                && activeSession.currentRound().definition().id().equals("elytra")) {
            cancelSession(activeSession, "ELYTRA_CONFIG_RELOAD", true);
        }
    }

    public String status() {
        int playerCount = activeSession == null ? Math.max(1, config.global().pregame().minimumPlayers()) : Math.max(1, activeSession.activeParticipantCount());
        String gameStatus = gameStatus(playerCount);
        if (activeSession == null) {
            return "HexMinigames: idle, available=" + available() + ", reason=" + availabilityReason() + ", scoreStorage=" + scores.backendName()
                    + "\n" + gameStatus;
        }
        RoundSession round = activeSession.currentRound();
        String selected = activeSession.selectedGames().isEmpty()
                ? "-"
                : activeSession.selectedGames().stream().map(MinigameDefinition::id).collect(Collectors.joining(","));
        return "HexMinigames: sessionType=" + activeSession.mode().statusLabel()
                + ", state=" + activeSession.state()
                + ", mode=" + activeSession.mode()
                + ", participants=" + activeSession.participants().size()
                + ", requiredPregamePlayers=" + requiredPregamePlayers(activeSession)
                + ", drawRemaining=" + (activeSession.state() == SeriesState.PRE_GAME_DRAW ? secondsRemaining(activeSession) : "-")
                + ", countdownRemaining=" + (activeSession.state() == SeriesState.PRE_GAME_COUNTDOWN ? secondsRemaining(activeSession) : "-")
                + ", selectedGames=" + selected
                + ", round=" + (round == null ? "-" : round.roundNumber() + "/" + round.definition().id())
                + "\n" + gameStatus;
    }

    private String gameStatus(int playerCount) {
        List<MinigameDefinition> selectable = registry.eligible(playerCount, false, false);
        Set<String> selectableIds = selectable.stream().map(MinigameDefinition::id).collect(Collectors.toSet());
        List<String> lines = new ArrayList<>();
        lines.add("planned total=" + registry.configuredRealGameCount()
                + ", currently selectable count=" + selectable.size()
                + ", games-per-series=" + config.global().gamesPerSeries());
        for (MinigameDefinition definition : registry.definitions()) {
            boolean availableGame = false;
            String reason = definition.implemented() ? "" : "not-registered";
            if (definition.implemented()) {
                MinigameAvailability availability = registry.create(definition.id())
                        .map(game -> game.availability(definition, playerCount))
                        .orElse(MinigameAvailability.unavailable("Factory missing"));
                availableGame = availability.available()
                        && (!definition.internal() || definition.enabled())
                        && (definition.internal() || definition.region().isPresent())
                        && (definition.internal() || !definition.participantSpawns().isEmpty())
                        && (definition.internal() || definition.spectatorSpawn().isPresent())
                        && definition.playerCountAllowed(playerCount);
                reason = availability.available() ? "" : availability.reason();
            }
            lines.add(definition.id()
                    + ": registered=" + definition.implemented()
                    + ", enabled=" + definition.enabled()
                    + ", available=" + availableGame
                    + ", selectable=" + selectableIds.contains(definition.id())
                    + dalgonaPatternStatus(definition)
                    + (reason == null || reason.isBlank() ? "" : ", reason=" + reason));
        }
        return String.join("\n", lines);
    }

    private String dalgonaPatternStatus(MinigameDefinition definition) {
        if (!DalgonaConfig.ID.equals(definition.id())) return "";
        List<String> errors = new ArrayList<>();
        DalgonaConfig loaded = DalgonaConfig.fromDefinition(definition, errors);
        return ", Dalgona patterns loaded: " + loaded.patterns().size();
    }

    public String debugState(UUID playerId) {
        boolean snapshot = false;
        try {
            snapshot = snapshots.available() && snapshots.find(playerId).isPresent();
        } catch (Throwable ignored) {
        }
        if (activeSession == null || !activeSession.contains(playerId)) {
            return "player=" + playerId + ", session=none, snapshot=" + snapshot;
        }
        RoundSession round = activeSession.currentRound();
        return "player=" + playerId
                + ", session=" + activeSession.instanceId()
                + ", state=" + activeSession.state()
                + ", round=" + (round == null ? "-" : round.definition().id())
                + ", roundPlayerState=" + (round == null ? "-" : round.playerState(playerId))
                + ", snapshot=" + snapshot;
    }

    public String stateName(UUID instanceId) {
        if (activeSession == null || !activeSession.instanceId().equals(instanceId)) return "NONE";
        return activeSession.state().name();
    }

    public int activeCount(UUID instanceId) {
        if (activeSession == null || !activeSession.instanceId().equals(instanceId)) return 0;
        return activeSession.participants().size();
    }

    public boolean activeSessionContains(UUID playerId) {
        return activeSession != null && activeSession.contains(playerId);
    }

    private boolean route(Player player, Cancellable event, RouteCall routeCall) {
        if (player == null || activeSession == null || !activeSession.contains(player.getUniqueId())) return false;
        RoundSession round = activeSession.currentRound();
        if (round == null) {
            event.setCancelled(true);
            return true;
        }
        if (round.playerState(player.getUniqueId()) == RoundPlayerState.GHOST) {
            event.setCancelled(true);
            return true;
        }
        EventDecision decision = routeCall.call(round);
        if (decision != EventDecision.ALLOW) {
            event.setCancelled(true);
            return true;
        }
        return true;
    }

    private void tick() {
        MinigamesSession session = activeSession;
        if (session == null) return;
        switch (session.state()) {
            case PRE_GAME_WAITING -> tickPregameWaiting(session);
            case PRE_GAME_DRAW -> tickPregameDraw(session);
            case PRE_GAME_COUNTDOWN -> tickPregameCountdown(session);
            case ROUND_COUNTDOWN -> tickCountdown(session);
            case ROUND_RUNNING -> tickRunning(session);
            case ROUND_RESULTS -> tickTimedState(session, this::afterRoundResults);
            case INTERMISSION -> tickTimedState(session, this::prepareNextRound);
            case SERIES_RESULTS -> tickTimedState(session, this::completeSession);
            default -> {
            }
        }
    }

    private void tickPregameWaiting(MinigamesSession session) {
        if (session.canBeginPregame(requiredPregamePlayers(session))) {
            beginPregameDraw(session);
        }
    }

    private void tickPregameDraw(MinigamesSession session) {
        if (abortPregameIfTooFew(session)) return;
        sendPregameDrawActionbar(session);
        if (session.stateTicksRemaining() <= 0) {
            freezeSelectedGamesAfterDraw(session);
            return;
        }
        session.decrementStateTicks();
    }

    private void tickPregameCountdown(MinigamesSession session) {
        if (abortPregameIfTooFew(session)) return;
        if (session.stateTicksRemaining() % 20 == 0) {
            int seconds = session.stateTicksRemaining() / 20;
            sendPregameCountdownSubtitle(session, seconds);
        }
        if (session.stateTicksRemaining() <= 0) {
            prepareNextRound(session);
            return;
        }
        session.decrementStateTicks();
    }

    private void beginPregameDraw(MinigamesSession session) {
        if (activeSession != session) return;
        if (!session.canBeginPregame(requiredPregamePlayers(session))) return;
        transition(session, SeriesState.PRE_GAME_DRAW, config.global().pregame().drawDurationSeconds() * 20);
    }

    private void freezeSelectedGamesAfterDraw(MinigamesSession session) {
        clearPregameActionbar(session);
        int gamesToSelect = gamesPerSeries(session);
        if (session.mode() != SessionMode.DEVELOPMENT_TEST || session.selectedGames().isEmpty()) {
            int participants = session.activeParticipantCount();
            List<MinigameDefinition> eligible = registry.eligible(participants, false, false);
            if (eligible.size() < gamesToSelect) {
                String message = "Not enough eligible minigames: " + eligible.size() + "/" + gamesToSelect;
                plugin.getLogger().warning(message);
                cancelSession(session, message, true);
                return;
            }
            session.replaceSelectedGames(selector.select(eligible, gamesToSelect));
        }
        transition(session, SeriesState.PRE_GAME_COUNTDOWN, config.global().pregame().countdownSeconds() * 20);
    }

    private boolean abortPregameIfTooFew(MinigamesSession session) {
        if (!session.shouldAbortPregame(requiredPregamePlayers(session))) return false;
        broadcast(session, config.messages().raw("pregame-cancelled-too-few", "&cSeria zostala anulowana: za malo uczestnikow."));
        cancelSession(session, "TOO_FEW_PLAYERS_PRE_GAME", true);
        return true;
    }

    private void sendPregameDrawActionbar(MinigamesSession session) {
        String message = pregameDrawActionbarMessage(config.messages(), gamesPerSeries(session), registry.configuredRealGameCount());
        for (Player player : onlinePlayers(session)) {
            player.sendActionBar(Text.component(message));
        }
    }

    static String pregameDrawActionbarMessage(hex.minigames.config.Messages messages, int selected, int available) {
        hex.minigames.config.Messages safeMessages = messages == null ? new hex.minigames.config.Messages(Map.of()) : messages;
        return safeMessages.raw("pregame-draw-actionbar", "&7Trwa losowanie &e{selected} &7z &e{available} &7minigier...")
                .replace("{selected}", String.valueOf(selected))
                .replace("{available}", String.valueOf(available));
    }

    private void clearPregameActionbar(MinigamesSession session) {
        for (Player player : onlinePlayers(session)) {
            player.sendActionBar(Text.component(""));
        }
    }

    private void sendPregameCountdownSubtitle(MinigamesSession session, int seconds) {
        String subtitle = Text.color(config.messages().raw("pregame-countdown-subtitle", "&e{seconds}")
                .replace("{seconds}", String.valueOf(seconds)));
        for (Player player : onlinePlayers(session)) {
            player.sendTitle("", subtitle, 0, 25, 0);
            if (shouldPlayPregameCountdownSound(seconds, config.global().pregame())) {
                play(player, config.global().pregame().countdownSound(), "pre-game countdown sound");
            }
        }
    }

    static boolean shouldPlayPregameCountdownSound(int seconds, PregameConfig pregame) {
        return pregame != null
                && seconds >= 0
                && seconds <= pregame.countdownSeconds()
                && pregame.countdownSound() != null
                && pregame.countdownSound().enabled();
    }

    private void tickCountdown(MinigamesSession session) {
        RoundSession round = session.currentRound();
        if (round == null) return;
        RoundContext context = new RoundContext(this, round);
        if (session.stateTicksRemaining() % 20 == 0) {
            int seconds = session.stateTicksRemaining() / 20;
            if (!round.minigame().handleCountdownTick(context, session.stateTicksRemaining())) {
                for (Player player : onlinePlayers(session)) {
                    player.sendTitle(
                            Text.color(config.messages().raw("round-countdown-title", "&e{game}").replace("{game}", round.definition().displayName())),
                            Text.color(config.messages().raw("round-countdown-subtitle", "&7Start za &f{seconds}s").replace("{seconds}", String.valueOf(seconds))),
                            0,
                            20,
                            0
                    );
                }
            }
        }
        if (session.stateTicksRemaining() <= 0) {
            startRound(session, round);
            return;
        }
        session.decrementStateTicks();
    }

    private void tickRunning(MinigamesSession session) {
        RoundSession round = session.currentRound();
        if (round == null) return;
        if (round.startDelayRemaining() > 0 && !round.finishRequested() && round.participantCount() > 0) {
            if (round.startDelayRemaining() % 2 == 0) {
                int seconds = (round.startDelayRemaining() + 19) / 20;
                for (Player player : onlinePlayers(session)) {
                    player.sendActionBar(Text.component("&fStart za: &e" + seconds + " s"));
                }
            }
            round.tickStartDelay();
            return;
        }
        round.tickElapsed();
        RoundContext context = new RoundContext(this, round);
        try {
            round.minigame().handleTick(context);
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Minigame tick failed for " + round.definition().id(), error);
            round.requestFinish(RoundEndReason.MINIGAME_REQUEST);
        }
        if (round.finishRequested()) {
            finishRound(session, round, round.requestedReason());
        } else if (round.participantCount() == 0) {
            finishRound(session, round, RoundEndReason.PLAYER_QUIT);
        } else if (round.minigame().finishWhenAllActiveResolved() && round.allActiveResolved()) {
            finishRound(session, round, RoundEndReason.ALL_ELIMINATED);
        } else if (round.timeLimitReached()) {
            finishRound(session, round, RoundEndReason.TIME_LIMIT);
        }
    }

    private void tickTimedState(MinigamesSession session, TimedStateFinished callback) {
        if (session.stateTicksRemaining() <= 0) {
            callback.run(session);
            return;
        }
        session.decrementStateTicks();
    }

    private String lastStartFailure;

    private String startSession(UUID instanceId, SessionMode mode, EventExecutionContext context, Set<UUID> participants, List<MinigameDefinition> selected, boolean usePregame) {
        lastStartFailure = null;
        if (!available()) return availabilityReason();
        if (participants.size() > config.global().maxPlayers()) return maxPlayersMessage();
        for (MinigameDefinition definition : selected) {
            if (!definition.playerCountAllowed(participants.size())) {
                return "Player count " + participants.size() + " is not allowed for minigame: " + definition.id();
            }
        }
        List<UUID> savedSnapshots = new ArrayList<>();
        try {
            for (UUID playerId : participants) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) continue;
                if (snapshots.find(playerId).isPresent()) {
                    rollbackSnapshots(savedSnapshots);
                    return config.messages().raw("snapshot-existing", "Pending inventory restore.");
                }
                if (!snapshots.saveIfAbsent(player, instanceId)) {
                    rollbackSnapshots(savedSnapshots);
                    return config.messages().raw("snapshot-existing", "Pending inventory restore.");
                }
                savedSnapshots.add(playerId);
            }
        } catch (Throwable error) {
            rollbackSnapshots(savedSnapshots);
            plugin.getLogger().severe("Could not persist minigames snapshot: " + rootMessage(error));
            return config.messages().raw("snapshot-save-failed", "Snapshot save failed.");
        }

        Map<UUID, Integer> startingGlobalScores = new LinkedHashMap<>();
        for (UUID playerId : participants) {
            try {
                startingGlobalScores.put(playerId, scores.getGlobalPoints(playerId));
            } catch (Throwable error) {
                plugin.getLogger().warning("Could not read minigames global score for " + playerId + ": " + rootMessage(error));
                startingGlobalScores.put(playerId, 0);
            }
        }

        MinigamesSession session = new MinigamesSession(instanceId, mode, context, participants, selected, startingGlobalScores);
        activeSession = session;
        for (Player player : onlinePlayers(session)) {
            session.rememberName(player.getUniqueId(), player.getName());
            prepareMinigameState(player);
        }
        if (usePregame) {
            for (Player player : onlinePlayers(session)) {
                teleportToPregame(player);
                showPregameBossBar(player);
            }
            transition(session, SeriesState.PRE_GAME_WAITING, 0);
            if (session.canBeginPregame(requiredPregamePlayers(session))) {
                beginPregameDraw(session);
            }
        } else {
            transition(session, SeriesState.SELECTING_GAMES, 0);
            prepareNextRound(session);
        }
        return activeSession == session ? null : lastStartFailure == null ? "Session preparation failed; check the console." : lastStartFailure;
    }

    private void prepareNextRound(MinigamesSession session) {
        if (activeSession != session) return;
        clearPregameBossBars();
        if (!session.hasNextRound()) {
            showSeriesResults(session);
            return;
        }
        if (session.mode() == SessionMode.EVENT && session.activeParticipantCount() < 2) {
            finishSessionEarly(session, "TOO_FEW_CONTINUATION_PLAYERS");
            return;
        }
        int roundNumber = session.nextRoundNumber();
        MinigameDefinition definition = session.consumeNextGame();
        Optional<Minigame> created = registry.create(definition.id());
        if (created.isEmpty()) {
            cancelSession(session, "Missing minigame factory: " + definition.id(), true);
            return;
        }
        session.markSeriesStarted();
        transition(session, SeriesState.PREPARING_ROUND, 0);
        RoundSession round = new RoundSession(roundNumber, definition, created.get(), session.participants(), definition.roundTimeSeconds() * 20);
        session.currentRound(round);
        teleportRoundParticipants(session, round);
        try {
            round.minigame().prepare(new RoundContext(this, round));
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Minigame prepare failed for " + definition.id(), error);
            lastStartFailure = "Blad przygotowania " + definition.id() + ": " + rootMessage(error);
            broadcast(session, "&c" + lastStartFailure);
            cancelSession(session, lastStartFailure, true);
            return;
        }
        int countdownSeconds = Math.max(0, round.minigame().countdownSeconds(definition, config.global().roundCountdownSeconds()));
        transition(session, SeriesState.ROUND_COUNTDOWN, countdownSeconds * 20);
    }

    private void startRound(MinigamesSession session, RoundSession round) {
        if (round.started()) return;
        round.started(true);
        session.markSeriesStarted();
        transition(session, SeriesState.ROUND_RUNNING, 0);
        try {
            round.minigame().start(new RoundContext(this, round));
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Minigame start failed for " + round.definition().id(), error);
            finishRound(session, round, RoundEndReason.MINIGAME_REQUEST);
            return;
        }
        broadcast(session, config.messages().raw("round-start", "&aStart rundy: &f{game}").replace("{game}", round.definition().displayName()));
    }

    private void finishRound(MinigamesSession session, RoundSession round, RoundEndReason reason) {
        if (activeSession != session || !round.markCleanedUp()) return;
        for (UUID playerId : round.participants()) pendingRoundRespawns.remove(playerId);
        RoundResult result;
        RoundContext context = new RoundContext(this, round);
        try {
            result = round.minigame().finish(context, reason);
        } catch (Throwable error) {
            plugin.getLogger().warning("Minigame finish failed for " + round.definition().id() + ": " + rootMessage(error));
            result = RoundResult.empty();
        }
        scores.applyRoundResult(session, round, result);
        try {
            round.minigame().reset(context);
        } catch (Throwable error) {
            plugin.getLogger().warning("Minigame reset failed for " + round.definition().id() + ": " + rootMessage(error));
        }
        for (Player player : onlinePlayers(session)) {
            applyActiveRoundState(player);
            player.setVelocity(new org.bukkit.util.Vector());
            player.setAllowFlight(true);
            player.setFlying(true);
            PlayerRoundResult outcome = result.players().get(player.getUniqueId());
            if (outcome != null && (outcome.completed() || outcome.failed())) {
                announceOutcome(round, player, outcome.completed(), true);
            }
        }
        sendRoundRanking(session, result);
        broadcast(session, config.messages().raw("round-results", "&eKoniec rundy: &f{game}").replace("{game}", round.definition().displayName()));
        transition(session, SeriesState.ROUND_RESULTS, config.global().roundResultsSeconds() * 20);
    }

    private void afterRoundResults(MinigamesSession session) {
        session.currentRound(null);
        if (!session.hasNextRound()) {
            showSeriesResults(session);
            return;
        }
        transition(session, SeriesState.INTERMISSION, config.global().intermissionSeconds() * 20);
    }

    private void showSeriesResults(MinigamesSession session) {
        session.currentRound(null);
        for (Player player : onlinePlayers(session)) {
            applyActiveRoundState(player);
            teleportToPregame(player);
        }
        broadcast(session, config.messages().raw("series-results", "&6Koniec serii HexMinigames."));
        broadcast(session, config.messages().raw("series-ranking-header", "&d&lPODSUMOWANIE SERII"));
        List<UUID> ranking = session.ranking();
        for (int i = 0; i < ranking.size(); i++) {
            UUID id = ranking.get(i);
            String row = config.messages().raw("series-ranking-row", "&e{place}. &f{player} &7- &a{points} pkt{status}")
                    .replace("{place}", String.valueOf(i + 1)).replace("{player}", session.playerName(id))
                    .replace("{points}", String.valueOf(session.seriesScore().points(id)))
                    .replace("{status}", session.forfeited(id) ? config.messages().raw("series-forfeited", " &8(opuscil serie)") : "");
            broadcast(session, row);
        }
        ranking.stream().filter(id -> !session.forfeited(id)).findFirst().ifPresent(winner -> {
            if (celebration != null) celebration.show(onlinePlayers(session),
                    config.global().pregame().spawn().toLocation(config.global().worldName()), session.playerName(winner), config.messages());
        });
        scores.commitEligibleSeries(session);
        transition(session, SeriesState.SERIES_RESULTS, config.global().seriesResultsSeconds() * 20);
    }

    private void completeSession(MinigamesSession session) {
        if (activeSession != session || !session.markTerminalNotified()) return;
        chatBlocked.removeAll(session.participants());
        clearPregameBossBars();
        transition(session, SeriesState.FINISHED, 0);
        scores.commitEligibleSeries(session);
        if (session.mode() == SessionMode.EVENT && eventsBridge != null) {
            boolean completed = eventsBridge.complete(session.instanceId(), eventResult(session, EventOutcome.SUCCESS));
            if (!completed) plugin.getLogger().warning("HexEvents refused HexMinigames completion for " + session.instanceId());
        }
        restoreSessionPlayers(session);
        activeSession = null;
    }

    private void cancelSession(MinigamesSession session, String reason, boolean notifyHexEvents) {
        if (activeSession != session || !session.markTerminalNotified()) return;
        chatBlocked.removeAll(session.participants());
        clearPregameBossBars();
        clearPregameActionbar(session);
        transition(session, SeriesState.CANCELLED, 0);
        RoundSession round = session.currentRound();
        if (round != null) {
            try {
                round.minigame().reset(new RoundContext(this, round));
            } catch (Throwable error) {
                plugin.getLogger().warning("Minigame reset during cancel failed: " + rootMessage(error));
            }
        }
        if (notifyHexEvents && session.mode() == SessionMode.EVENT && eventsBridge != null) {
            eventsBridge.fail(session.instanceId(), new EventFailure("MINIGAMES_CANCELLED", reason, false));
        }
        restoreSessionPlayers(session);
        activeSession = null;
    }

    private EventResult eventResult(MinigamesSession session, EventOutcome outcome) {
        List<ResultSubject> subjects = session.participants().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(playerId -> new ResultSubject(
                        ResultSubjectType.PLAYER,
                        playerId,
                        Map.of("points", (double) session.seriesScore().points(playerId)),
                        Set.of("MINIGAMES_SERIES")
                ))
                .toList();
        String games = session.selectedGames().stream().map(MinigameDefinition::id).collect(Collectors.joining(","));
        return new EventResult(outcome, subjects, Map.of("games", games));
    }

    private void restoreSessionPlayers(MinigamesSession session) {
        for (UUID playerId : session.participants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) restoreOnline(player, true);
            else markPending(playerId);
        }
        markInstanceSnapshotsPending(session.instanceId());
    }

    private void restoreOnline(Player player, boolean deleteAfterRestore) {
        UUID playerId = player.getUniqueId();
        restoring.add(playerId);
        try {
            Optional<StoredPlayerState> state = snapshots.find(playerId);
            if (state.isEmpty()) {
                fallbackRestore(player);
                return;
            }
            if (!PlayerSnapshotRepository.restore(player, state.get())) {
                snapshots.markRestorePending(playerId);
                player.sendMessage(config.messages().get("restore-failed", "&cRestore failed."));
                return;
            }
            if (deleteAfterRestore) snapshots.delete(playerId);
            player.setCollidable(true);
            player.sendMessage(config.messages().get("restore-success", "&aRestored."));
        } catch (Throwable error) {
            markPending(playerId);
            plugin.getLogger().warning("Could not restore minigames snapshot for " + player.getName() + ": " + rootMessage(error));
            player.sendMessage(config.messages().get("restore-failed", "&cRestore failed."));
        } finally {
            Bukkit.getScheduler().runTaskLater(plugin, () -> restoring.remove(playerId), 2L);
        }
    }

    private void restoreBeforeDisconnect(Player player) {
        try {
            Optional<StoredPlayerState> state = snapshots.find(player.getUniqueId());
            if (state.isPresent() && PlayerSnapshotRepository.restore(player, state.get())) {
                snapshots.delete(player.getUniqueId());
                return;
            }
            markPending(player.getUniqueId());
        } catch (Throwable error) {
            markPending(player.getUniqueId());
            plugin.getLogger().warning("Could not restore minigames snapshot before disconnect for " + player.getName() + ": " + rootMessage(error));
        }
    }

    private void fallbackRestore(Player player) {
        player.getInventory().clear();
        player.setItemOnCursor(new ItemStack(Material.AIR));
        player.setGameMode(Bukkit.getDefaultGameMode());
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(true);
        player.updateInventory();
    }

    private void markInstanceSnapshotsPending(UUID instanceId) {
        try {
            for (StoredPlayerState snapshot : snapshots.findByInstance(instanceId)) {
                snapshots.markRestorePending(snapshot.playerId());
            }
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not mark minigames snapshots pending for " + instanceId + ": " + rootMessage(error));
        }
    }

    private void markPending(UUID playerId) {
        try {
            snapshots.markRestorePending(playerId);
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not mark minigames snapshot pending for " + playerId + ": " + rootMessage(error));
        }
    }

    private void removeParticipant(MinigamesSession session, UUID playerId, RoundEndReason reason, boolean forfeit) {
        unblockChat(playerId);
        pregameBossBars.remove(playerId);
        RoundSession round = session.currentRound();
        if (round != null) {
            try {
                round.minigame().handlePlayerQuit(new RoundContext(this, round), playerId);
            } catch (Throwable error) {
                plugin.getLogger().warning("Minigame quit handler failed for " + playerId + ": " + rootMessage(error));
            }
        }
        if (forfeit && session.state() != SeriesState.SERIES_RESULTS) session.forfeitParticipant(playerId);
        else session.removeParticipant(playerId);
        if (session.shouldAbortPregame(requiredPregamePlayers(session))) {
            broadcast(session, config.messages().raw("pregame-cancelled-too-few", "&cSeria zostala anulowana: za malo uczestnikow."));
            cancelSession(session, "TOO_FEW_PLAYERS_PRE_GAME", true);
            return;
        }
        if (session.shouldFinishForMinimumContinuation(Math.max(2, config.global().series().minimumContinuationPlayers()))) {
            finishSessionEarly(session, "TOO_FEW_CONTINUATION_PLAYERS");
            return;
        }
        if (round != null && round.participantCount() == 0) {
            round.requestFinish(reason);
        }
    }

    private void finishSessionEarly(MinigamesSession session, String reason) {
        if (activeSession != session || !session.markTerminalNotified()) return;
        chatBlocked.removeAll(session.participants());
        clearPregameBossBars();
        broadcast(session, config.messages().raw("series-ended-too-few", "&eSeria HexMinigames zakonczyla sie wczesniej: zostal za malo uczestnikow."));
        RoundSession round = session.currentRound();
        if (round != null) {
            try {
                round.minigame().reset(new RoundContext(this, round));
            } catch (Throwable error) {
                plugin.getLogger().warning("Minigame reset during early finish failed: " + rootMessage(error));
            }
        }
        transition(session, SeriesState.FINISHED, 0);
        scores.commitEligibleSeries(session);
        if (session.mode() == SessionMode.EVENT && eventsBridge != null) {
            EventResult result = new EventResult(EventOutcome.SUCCESS, eventResult(session, EventOutcome.SUCCESS).subjects(), Map.of("early_finish", reason));
            boolean completed = eventsBridge.complete(session.instanceId(), result);
            if (!completed) plugin.getLogger().warning("HexEvents refused early HexMinigames completion for " + session.instanceId());
        }
        restoreSessionPlayers(session);
        activeSession = null;
    }

    private void teleportRoundParticipants(MinigamesSession session, RoundSession round) {
        List<LocationSpec> spawns = round.definition().internal()
                ? List.of(config.global().debugSpawn())
                : round.definition().participantSpawns();
        int index = 0;
        for (UUID playerId : round.participants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            LocationSpec spec = spawns.isEmpty() ? LocationSpec.missing() : spawns.get(index++ % spawns.size());
            Location target = spec.toLocation(config.global().worldName());
            if (target == null) {
                plugin.getLogger().warning("Missing spawn for minigame " + round.definition().id() + "; skipping teleport for " + player.getName());
                continue;
            }
            applyActiveRoundState(player);
            if ("popcorn".equals(round.definition().id())) target.add(0, 1, 0);
            teleportInternal(player, target);
        }
    }

    private void keepInPregame(Player player, Location target) {
        if (pregameContains(target)) return;
        teleportToPregame(player);
    }

    private boolean pregameContains(Location location) {
        if (config == null || !config.global().pregame().enabled()) return false;
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(config.global().worldName())) return false;
        return config.global().pregame().region() != null
                && config.global().pregame().region().contains(location);
    }

    private void teleportToPregame(Player player) {
        Location target = config.global().pregame().spawn().toLocation(config.global().worldName());
        if (target == null) {
            plugin.getLogger().warning("Cannot teleport " + player.getName() + " to pregame room: spawn/world is unavailable.");
            return;
        }
        teleportInternal(player, target);
    }

    private void showPregameBossBar(Player player) {
        if (config == null || !config.global().pregame().enabled()) return;
        pregameBossBars.show(player, new BossBarSettings(
                config.global().pregame().bossBarTitle(),
                config.global().pregame().bossBarColor(),
                config.global().pregame().bossBarStyle()
        ));
    }

    private void clearPregameBossBars() {
        pregameBossBars.clear();
    }

    private boolean isPregameState(SeriesState state) {
        return state == SeriesState.PRE_GAME_WAITING
                || state == SeriesState.PRE_GAME_DRAW
                || state == SeriesState.PRE_GAME_COUNTDOWN;
    }

    private int secondsRemaining(MinigamesSession session) {
        return Math.max(0, (int) Math.ceil(session.stateTicksRemaining() / 20.0));
    }

    private void prepareMinigameState(Player player) {
        player.setItemOnCursor(new ItemStack(Material.AIR));
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setExtraContents(null);
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(true);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        player.setHealth(Math.max(0.1, player.getMaxHealth()));
        player.updateInventory();
    }

    private void applyActiveRoundState(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(true);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
    }

    private void applyGhost(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(config.global().ghostAllowFlight());
        player.setFlying(config.global().ghostAllowFlight());
        player.setCollidable(false);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
    }

    private void applyRespawnDelay(Player player) {
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setCollidable(false);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
    }

    private void keepGhostInRegion(Player player, RoundSession round) {
        if (round.definition().region().isPresent() && round.definition().region().get().contains(player.getLocation())) return;
        Location target = round.definition().internal()
                ? config.global().debugSpawn().toLocation(config.global().worldName())
                : round.definition().spectatorSpawn().map(spawn -> spawn.toLocation(config.global().worldName())).orElse(null);
        if (target != null) teleportInternal(player, target);
    }

    private void teleportInternal(Player player, Location target) {
        UUID playerId = player.getUniqueId();
        internalTeleports.add(playerId);
        player.teleport(target);
        Bukkit.getScheduler().runTaskLater(plugin, () -> internalTeleports.remove(playerId), 2L);
    }

    /** Defers penalty teleports so the movement packet cannot overwrite the respawn location. */
    public void respawnRoundPlayer(RoundSession round, Player player, Location target, Runnable afterArrival) {
        if (target == null || target.getWorld() == null) throw new IllegalArgumentException("Respawn world is unavailable");
        UUID playerId = player.getUniqueId();
        Object token = new Object();
        pendingRoundRespawns.put(playerId, token);
        Location destination = target.clone();
        new org.bukkit.scheduler.BukkitRunnable() {
            private int attempts;
            private int waitingTicks;

            @Override
            public void run() {
                if (pendingRoundRespawns.get(playerId) != token || !player.isOnline()
                        || activeSession == null || activeSession.currentRound() != round
                        || activeSession.state() != SeriesState.ROUND_RUNNING || !round.participants().contains(playerId)) {
                    pendingRoundRespawns.remove(playerId, token);
                    cancel();
                    return;
                }
                if (waitingTicks-- > 0) return;
                internalTeleports.add(playerId);
                boolean arrived;
                try {
                    arrived = player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
                } finally {
                    internalTeleports.remove(playerId);
                }
                if (arrived) {
                    player.setVelocity(new org.bukkit.util.Vector());
                    player.setFallDistance(0);
                    player.setFireTicks(0);
                    pendingRoundRespawns.remove(playerId, token);
                    cancel();
                    if (afterArrival != null) afterArrival.run();
                } else {
                    if (++attempts == 3) plugin.getLogger().warning("Respawn teleport cancelled for " + player.getName() + " in " + round.definition().id());
                    waitingTicks = attempts >= 3 ? 19 : 0;
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private Set<UUID> mergedParticipants(EventExecutionContext context) {
        Set<UUID> participants = new LinkedHashSet<>(context.registeredPlayers());
        participants.addAll(pendingParticipants.getOrDefault(context.instanceId(), Set.of()));
        return participants;
    }

    private boolean hexEventsAdmissionWouldExceedMaxPlayers(EventJoinRequest request) {
        Set<UUID> participants = new LinkedHashSet<>();
        if (request.context() != null) participants.addAll(request.context().registeredPlayers());
        participants.addAll(pendingParticipants.getOrDefault(request.instanceId(), Set.of()));
        participants.remove(request.playerId());
        return participants.size() + 1 > config.global().maxPlayers();
    }

    private String maxPlayersMessage() {
        return config.messages().raw("max-players-reached", "HexMinigames player limit reached: {max}.")
                .replace("{max}", String.valueOf(config.global().maxPlayers()));
    }

    private int requiredPregamePlayers(MinigamesSession session) {
        if (session != null && session.mode() == SessionMode.ADMIN_TEST) {
            return 1;
        }
        if (session != null && session.mode() == SessionMode.DEVELOPMENT_TEST) {
            return config.global().developmentMinimumPlayers();
        }
        return Math.max(4, config.global().pregame().minimumPlayers());
    }

    private int gamesPerSeries(MinigamesSession session) {
        if (session != null && session.mode() == SessionMode.ADMIN_TEST) return 5;
        return session != null && session.mode() == SessionMode.DEVELOPMENT_TEST ? 1 : config.global().gamesPerSeries();
    }

    private String eligibleGamesFailure(int participants, int requiredGames) {
        int eligible = registry.eligible(participants, false, false).size();
        if (eligible >= requiredGames) return null;
        return "Not enough eligible minigames: " + eligible + "/" + requiredGames;
    }

    private Set<UUID> onlineOnly(Set<UUID> playerIds) {
        Set<UUID> out = new LinkedHashSet<>();
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) out.add(playerId);
        }
        return out;
    }

    private List<Player> onlinePlayers(MinigamesSession session) {
        List<Player> out = new ArrayList<>();
        for (UUID playerId : session.participants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) out.add(player);
        }
        return out;
    }

    private Optional<MinigameDefinition> definitionForAdmin(String gameId) {
        if (DebugMinigame.ID.equalsIgnoreCase(gameId)) {
            return registry.definition(DebugMinigame.ID).map(this::debugDefinition);
        }
        return registry.definition(gameId);
    }

    private MinigameDefinition debugDefinition(MinigameDefinition definition) {
        return new MinigameDefinition(
                definition.id(),
                "DebugMinigame",
                config.global().debugEnabled(),
                definition.implemented(),
                true,
                1,
                0,
                1,
                Optional.empty(),
                List.of(config.global().debugSpawn()),
                Optional.of(config.global().debugSpawn()),
                config.global().debugGameDurationSeconds(),
                Map.of("debug", true),
                "internal"
        );
    }

    private World world() {
        if (config == null || config.global().worldName() == null) return null;
        return Bukkit.getWorld(config.global().worldName());
    }

    private List<String> allConfigErrors() {
        List<String> errors = new ArrayList<>(config.errors());
        errors.addAll(config.global().errors());
        return errors;
    }

    private void broadcast(MinigamesSession session, String message) {
        String colored = Text.color(config.messages().prefix() + message);
        for (Player player : onlinePlayers(session)) {
            player.sendMessage(colored);
        }
    }

    private void playRoundEndSound(MinigamesSession session) {
        ConfiguredSound configured = config.global().roundEndSound();
        for (Player player : onlinePlayers(session)) {
            play(player, configured, "round-end-sound");
        }
    }

    private void play(Player player, ConfiguredSound configured, String label) {
        if (player == null || configured == null || !configured.enabled()) return;
        Sound sound;
        try {
            sound = configured.bukkitSound();
        } catch (Throwable error) {
            plugin.getLogger().warning("Invalid " + label + ": " + configured.sound());
            return;
        }
        if (sound != null) player.playSound(player.getLocation(), sound, configured.volume(), configured.pitch());
    }

    private void sendRoundEndSubtitle(MinigamesSession session) {
        String subtitle = Text.color(roundEndSubtitle(config.messages()));
        for (Player player : onlinePlayers(session)) {
            player.sendTitle("", subtitle, 0, 70, 10);
        }
    }

    static String roundEndSubtitle(hex.minigames.config.Messages messages) {
        hex.minigames.config.Messages safeMessages = messages == null ? new hex.minigames.config.Messages(Map.of()) : messages;
        return safeMessages.raw("round-end-subtitle", "&6KONIEC");
    }

    private void sendRoundRanking(MinigamesSession session, RoundResult result) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID playerId : session.participants()) {
            Player player = Bukkit.getPlayer(playerId);
            names.put(playerId, player == null ? playerId.toString() : player.getName());
        }
        List<String> lines = roundRankingLines(result, session.seriesScore().snapshot(), names, config.messages().raw("round-ranking-header", "&d&lWYNIKI"));
        for (Player player : onlinePlayers(session)) {
            for (String line : lines) {
                player.sendMessage(Text.color(line));
            }
        }
    }

    static List<String> roundRankingLines(RoundResult result, Map<UUID, Integer> pendingSeriesScore, Map<UUID, String> playerNames, String header) {
        RoundResult safeResult = result == null ? RoundResult.empty() : result;
        Map<UUID, Integer> safeSeries = pendingSeriesScore == null ? Map.of() : pendingSeriesScore;
        Map<UUID, String> safeNames = playerNames == null ? Map.of() : playerNames;
        List<Map.Entry<UUID, PlayerRoundResult>> entries = new ArrayList<>(safeResult.players().entrySet());
        entries.sort((left, right) -> {
            int points = Integer.compare(right.getValue().points(), left.getValue().points());
            if (points != 0) return points;
            int tie = compareTieBreaker(left.getValue(), right.getValue());
            if (tie != 0) return tie;
            String leftName = safeNames.getOrDefault(left.getKey(), left.getKey().toString());
            String rightName = safeNames.getOrDefault(right.getKey(), right.getKey().toString());
            return leftName.compareToIgnoreCase(rightName);
        });

        List<String> lines = new ArrayList<>();
        lines.add(header == null || header.isBlank() ? "&d&lWYNIKI" : header);
        int place = 1;
        for (Map.Entry<UUID, PlayerRoundResult> entry : entries) {
            UUID playerId = entry.getKey();
            PlayerRoundResult round = entry.getValue();
            String name = safeNames.getOrDefault(playerId, playerId.toString());
            int roundPoints = round.points();
            int sum = safeSeries.getOrDefault(playerId, 0);
            String pointsText = roundPoints > 0 ? "+" + roundPoints + " pkt" : "0 pkt";
            lines.add("&f" + place + ". &e" + name + " &7" + pointsText + " &8| &fSuma: &d" + sum + " pkt");
            place++;
        }
        return lines;
    }

    private static int compareTieBreaker(PlayerRoundResult left, PlayerRoundResult right) {
        if (left.placement().isPresent() && right.placement().isPresent()) {
            int placement = Integer.compare(left.placement().orElse(Integer.MAX_VALUE), right.placement().orElse(Integer.MAX_VALUE));
            if (placement != 0) return placement;
        }
        int fastTime = compareLongData(left, right, "completion_time_ms", true);
        if (fastTime != 0) return fastTime;
        int finishTick = compareLongData(left, right, "finish_tick", true);
        if (finishTick != 0) return finishTick;
        int progress = compareLongData(left, right, "progress", false);
        if (progress != 0) return progress;
        return compareLongData(left, right, "meters", false);
    }

    private static int compareLongData(PlayerRoundResult left, PlayerRoundResult right, String key, boolean ascending) {
        Optional<Long> leftValue = longData(left, key);
        Optional<Long> rightValue = longData(right, key);
        if (leftValue.isEmpty() && rightValue.isEmpty()) return 0;
        if (leftValue.isEmpty()) return 1;
        if (rightValue.isEmpty()) return -1;
        int compared = Long.compare(leftValue.get(), rightValue.get());
        return ascending ? compared : -compared;
    }

    private static Optional<Long> longData(PlayerRoundResult result, String key) {
        if (result == null || key == null) return Optional.empty();
        String raw = result.data().get(key);
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private void transition(MinigamesSession session, SeriesState state, int ticks) {
        session.state(state);
        session.stateTicksRemaining(ticks);
        if (config != null && config.global().debugLogStateTransitions()) {
            plugin.getLogger().info("HexMinigames session " + session.instanceId() + " -> " + state);
        }
    }

    private void rollbackSnapshots(List<UUID> savedSnapshots) {
        for (UUID playerId : savedSnapshots) {
            try {
                snapshots.delete(playerId);
            } catch (Throwable error) {
                plugin.getLogger().warning("Could not roll back minigames snapshot for " + playerId + ": " + rootMessage(error));
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface RouteCall {
        EventDecision call(RoundSession round);
    }

    @FunctionalInterface
    private interface TimedStateFinished {
        void run(MinigamesSession session);
    }
}

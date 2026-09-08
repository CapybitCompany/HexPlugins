package hexbuildbattle.game;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.arena.ArenaManager;
import hexbuildbattle.arena.ArenaResetService;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.config.PluginConfig;
import hexbuildbattle.config.SoundSetting;
import hexbuildbattle.effect.BuildBattleEffects;
import hexbuildbattle.judging.JudgingService;
import hexbuildbattle.player.PlayerStateService;
import hexbuildbattle.player.PlayerStatus;
import hexbuildbattle.proxy.ProxyTransferService;
import hexbuildbattle.queue.QueueManager;
import hexbuildbattle.result.ResultsService;
import hexbuildbattle.score.RoundBuildScore;
import hexbuildbattle.score.RoundPlacement;
import hexbuildbattle.score.ScoreService;
import hexbuildbattle.statistics.StatisticsService;
import hexbuildbattle.theme.Theme;
import hexbuildbattle.theme.ThemeManager;
import hexbuildbattle.theme.ThemeVotingService;
import hexbuildbattle.rating.RatingService;
import hexbuildbattle.util.TimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GameManager {

    private static final String COUNTDOWN_TASK = "countdown";
    private static final String LOBBY_ACTIONBAR_TASK = "lobby-actionbar";
    private static final String PLAYER_MAINTENANCE_TASK = "player-maintenance";
    private static final String THEME_SELECTED_DELAY_TASK = "theme-selected-delay";
    private static final String BUILDING_TASK = "building";
    private static final String SNOW_EFFECT_TASK = "snow-effect";
    private static final String PRE_JUDGING_DELAY_TASK = "pre-judging-delay";
    private static final String RESULTS_TASK = "results";
    private static final Set<Integer> COUNTDOWN_SUBTITLE_SECONDS = Set.of(
            30, 25, 20, 15, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    );
    private static final int BUILDING_FINAL_COUNTDOWN_SECONDS = 10;

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final MessageService messages;
    private final PlayerStateService playerStateService;
    private final QueueManager queueManager;
    private final GameTaskRegistry taskRegistry;
    private final ProxyTransferService proxyTransferService;
    private final GameSession session;
    private final GameStateController stateController;
    private final ArenaManager arenaManager;
    private final ArenaResetService arenaResetService;
    private final ThemeManager themeManager;
    private final ThemeVotingService themeVotingService;
    private final BuildSettingsService buildSettingsService;
    private final RatingService ratingService;
    private final BuildBattleEffects effects;
    private final JudgingService judgingService;
    private final ScoreService scoreService;
    private final ResultsService resultsService;
    private final StatisticsService statisticsService;

    public GameManager(
            JavaPlugin plugin,
            ConfigService configService,
            MessageService messages,
            PlayerStateService playerStateService,
            QueueManager queueManager,
            GameTaskRegistry taskRegistry,
            ProxyTransferService proxyTransferService,
            GameSession session,
            ArenaManager arenaManager,
            ArenaResetService arenaResetService,
            ThemeManager themeManager,
            ThemeVotingService themeVotingService,
            BuildSettingsService buildSettingsService,
            RatingService ratingService,
            BuildBattleEffects effects,
            JudgingService judgingService,
            ScoreService scoreService,
            ResultsService resultsService,
            StatisticsService statisticsService
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.messages = messages;
        this.playerStateService = playerStateService;
        this.queueManager = queueManager;
        this.taskRegistry = taskRegistry;
        this.proxyTransferService = proxyTransferService;
        this.session = session;
        this.stateController = new GameStateController(plugin.getLogger());
        this.arenaManager = arenaManager;
        this.arenaResetService = arenaResetService;
        this.themeManager = themeManager;
        this.themeVotingService = themeVotingService;
        this.buildSettingsService = buildSettingsService;
        this.ratingService = ratingService;
        this.effects = effects;
        this.judgingService = judgingService;
        this.scoreService = scoreService;
        this.resultsService = resultsService;
        this.statisticsService = statisticsService;
    }

    public void start() {
        arenaManager.reload();
        buildSettingsService.resetFloors(arenaManager.allArenas());
        themeManager.reload();
        ratingService.reload();
        restartLobbyActionBarTask();
        restartMaintenanceTask();
        restartSnowEffectTask();
        startWaitingCycle();
    }

    public void shutdown() {
        cancelPhaseServices();
        taskRegistry.cancelAll();
        arenaResetService.cancel();
        session.clearRound();
        queueManager.clear();
    }

    public GameSession session() {
        return session;
    }

    public void handleJoin(Player player) {
        prepareLobbyPlayer(player);

        GameState state = session.state();
        if (state.acceptsQueueForCurrentRound()) {
            enqueueForCurrentRound(player);
            evaluateCountdown();
            return;
        }

        playerStateService.mark(player.getUniqueId(), PlayerStatus.WAITING_NEXT);
        messages.sendActionBar(player, "join.waiting-next-actionbar", Map.of());
    }

    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        queueManager.remove(playerId);

        if (session.isParticipant(playerId) && session.state().isActiveRound()) {
            boolean keepBuild = configService.config().safety().judgeDisconnectedBuilds()
                    && session.state().ordinal() >= GameState.BUILDING.ordinal();
            session.removeActiveParticipant(playerId, keepBuild);
            if (session.state() == GameState.JUDGING) {
                judgingService.handleParticipantRemoved(playerId);
            }
            if (session.activeParticipants().isEmpty()) {
                resetAfterEmptyRound();
            }
        }

        playerStateService.remove(playerId);

        if (session.state() == GameState.COUNTDOWN) {
            evaluateCountdown();
        }
    }

    public void handleLobbyCommand(Player player) {
        UUID playerId = player.getUniqueId();
        queueManager.remove(playerId);
        if (session.isParticipant(playerId) && session.state().isActiveRound()) {
            session.removeActiveParticipant(playerId, false);
            if (session.state() == GameState.JUDGING) {
                judgingService.handleParticipantRemoved(playerId);
            }
            if (session.activeParticipants().isEmpty()) {
                resetAfterEmptyRound();
            }
        }
        playerStateService.mark(playerId, PlayerStatus.LOBBY);

        if (session.state() == GameState.COUNTDOWN) {
            evaluateCountdown();
        }

        messages.sendWithPrefix(player, "lobby.transfer", Map.of());
        if (!proxyTransferService.sendToLobby(player)) {
            messages.sendWithPrefix(player, "lobby.transfer-failed", Map.of());
        }
    }

    public boolean reloadConfiguration() {
        boolean reloaded = configService.reloadAll();
        if (!reloaded) {
            return false;
        }
        arenaManager.reload();
        themeManager.reload();
        ratingService.reload();
        restartLobbyActionBarTask();
        restartMaintenanceTask();
        restartSnowEffectTask();
        if (session.state().acceptsQueueForCurrentRound()) {
            evaluateCountdown();
        }
        return true;
    }

    public void sendStatus(CommandSender sender) {
        messages.sendList(sender, "command.status", Map.of(
                "phase", phasePlaceholder(),
                "queue", Integer.toString(queueManager.onlineSize()),
                "max", Integer.toString(configService.config().maxPlayers()),
                "participants", Integer.toString(session.participantCount()),
                "countdown", countdownPlaceholder(),
                "theme", themePlaceholder(),
                "time", timePlaceholder()
        ));
    }

    public void forceStart(CommandSender sender) {
        if (session.state() != GameState.WAITING && session.state() != GameState.COUNTDOWN) {
            messages.sendWithPrefix(sender, "command.forcestart-invalid-state", Map.of());
            return;
        }
        int queued = queueManager.onlineSize();
        int min = configService.config().minPlayers();
        if (queued < min) {
            messages.sendWithPrefix(sender, "command.forcestart-not-enough", Map.of(
                    "queue", Integer.toString(queued),
                    "min", Integer.toString(min)
            ));
            return;
        }
        messages.sendWithPrefix(sender, "command.forcestart-started", Map.of());
        completeCountdown();
    }

    public void forceStartTesting(CommandSender sender) {
        if (session.state() != GameState.WAITING && session.state() != GameState.COUNTDOWN) {
            messages.sendWithPrefix(sender, "command.forcetest-invalid-state", Map.of());
            return;
        }
        int queued = queueManager.onlineSize();
        if (queued < 1) {
            messages.sendWithPrefix(sender, "command.forcetest-empty", Map.of());
            return;
        }
        messages.sendWithPrefix(sender, "command.forcetest-started", Map.of(
                "queue", Integer.toString(queued),
                "min", Integer.toString(configService.config().minPlayers())
        ));
        completeCountdown(true);
    }

    public void stopRound(CommandSender sender) {
        messages.sendWithPrefix(sender, "command.stop-started", Map.of());
        startReset(session.usedArenas().isEmpty() ? arenaManager.allArenas() : session.usedArenas(), true);
    }

    public void resetArenas(CommandSender sender) {
        messages.sendWithPrefix(sender, "command.reset-started", Map.of());
        Collection<Arena> arenas = session.usedArenas().isEmpty() ? arenaManager.allArenas() : session.usedArenas();
        startReset(arenas, true);
    }

    public void skipBuild(CommandSender sender) {
        if (session.state() != GameState.BUILDING) {
            messages.sendWithPrefix(sender, "command.skipbuild-invalid-state", Map.of());
            return;
        }
        messages.sendWithPrefix(sender, "command.skipbuild-success", Map.of());
        finishBuildingPhase();
    }

    public boolean controlsPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        return queueManager.contains(playerId)
                || session.isParticipant(playerId)
                || playerStateService.isWaitingNext(playerId)
                || session.state().isActiveRound();
    }

    public boolean isActiveParticipant(UUID playerId) {
        return session.isActiveParticipant(playerId);
    }

    public Optional<Arena> assignedArena(UUID playerId) {
        return session.assignedArena(playerId);
    }

    public Optional<Arena> findUsedArenaByBlock(Block block) {
        return arenaManager.findByBlock(block, session.usedArenas());
    }

    public Optional<Arena> findUsedArenaByLocation(Location location) {
        if (location == null) {
            return Optional.empty();
        }
        return session.usedArenas().stream()
                .filter(arena -> arena.moduleRegion().contains(location))
                .findFirst();
    }

    public void rescuePlayer(Player player) {
        player.teleportAsync(rescueLocation(player));
        maintainPlayer(player);
    }

    public Location rescueLocation(Player player) {
        UUID playerId = player.getUniqueId();
        if (session.state() == GameState.BUILDING) {
            Optional<Arena> arena = assignedArena(playerId);
            if (arena.isPresent()) {
                return arena.get().ownerSpawn();
            }
        }
        if (session.state() == GameState.JUDGING && session.currentJudgedOwner() != null) {
            Optional<Arena> arena = assignedArena(session.currentJudgedOwner());
            if (arena.isPresent()) {
                return arena.get().judgingCenter();
            }
        }
        return lobbyLocation(player);
    }

    public void maintainPlayer(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);
        try {
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            double healthCap = maxHealth == null ? 20.0D : maxHealth.getValue();
            if (player.getHealth() < Math.min(20.0D, healthCap)) {
                player.setHealth(Math.min(20.0D, healthCap));
            }
        } catch (IllegalArgumentException ignored) {
            // Keep maintenance alive even if another plugin changes health state unusually.
        }

        UUID playerId = player.getUniqueId();
        GameState state = session.state();
        if (state == GameState.BUILDING && session.isActiveParticipant(playerId)) {
            if (!player.isOp()) {
                player.setGameMode(GameMode.CREATIVE);
                player.setAllowFlight(true);
            }
            buildSettingsService.enforceCompass(player);
            assignedArena(playerId).ifPresent(arena -> buildSettingsService.applyVisuals(player, arena));
        } else if (state == GameState.THEME_VOTING || state == GameState.PRE_JUDGING
                || state == GameState.JUDGING || state == GameState.RESULTS) {
            if (session.isActiveParticipant(playerId)) {
                if (!player.isOp()) {
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setAllowFlight(true);
                    if (state == GameState.JUDGING || state == GameState.RESULTS) {
                        player.setFlying(true);
                    }
                }
            }
        } else if (playerStateService.isWaitingNext(playerId) || queueManager.contains(playerId)) {
            if (!player.isOp()) {
                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    public String statusPlaceholder(Player player) {
        if (player == null) {
            return "";
        }
        UUID playerId = player.getUniqueId();
        if (playerStateService.isWaitingNext(playerId)) {
            return messages.raw("placeholder.status.waiting-next", "&cAreny sa zajete");
        }
        if (session.isActiveParticipant(playerId)) {
            return messages.raw("placeholder.status.active", "&aGra rozpoczeta");
        }
        return messages.raw("placeholder.status.waiting", "&eOczekiwanie na graczy");
    }

    public String queuePlaceholder() {
        return Integer.toString(queueManager.onlineSize());
    }

    public String participantsPlaceholder() {
        return Integer.toString(session.participantCount());
    }

    public String phasePlaceholder() {
        return messages.raw("placeholder.phase." + session.state().name(), session.state().name());
    }

    public String themePlaceholder() {
        if (session.selectedTheme() != null) {
            return session.selectedTheme().displayName();
        }
        if (session.state() == GameState.THEME_VOTING) {
            return configService.config().placeholders().votingTheme();
        }
        return configService.config().placeholders().noTheme();
    }

    public String timePlaceholder() {
        if (session.phaseRemainingSeconds() <= 0) {
            return "-";
        }
        return TimeFormatter.mmss(session.phaseRemainingSeconds());
    }

    private void startWaitingCycle() {
        cancelPhaseServices();
        session.clearRound();
        stateController.transition(session, GameState.WAITING);
        queueManager.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            prepareLobbyPlayer(player);
            enqueueForCurrentRound(player);
        }
        evaluateCountdown();
    }

    private void enqueueForCurrentRound(Player player) {
        if (!queueManager.enqueue(player)) {
            playerStateService.mark(player.getUniqueId(), PlayerStatus.LOBBY);
            messages.sendWithPrefix(player, "join.queue-full", Map.of());
            return;
        }

        playerStateService.mark(player.getUniqueId(), PlayerStatus.QUEUED);
        messages.sendWithPrefix(player, "join.queued", Map.of(
                "queue", Integer.toString(queueManager.onlineSize()),
                "max", Integer.toString(configService.config().maxPlayers())
        ));
    }

    private void evaluateCountdown() {
        int queued = queueManager.onlineSize();
        int minPlayers = configService.config().minPlayers();

        if (session.state() == GameState.WAITING && queued >= minPlayers) {
            startCountdown();
            return;
        }

        if (session.state() == GameState.COUNTDOWN && queued < minPlayers) {
            cancelCountdown();
        }
    }

    private void startCountdown() {
        stateController.transition(session, GameState.COUNTDOWN);
        int seconds = configService.config().timings().countdownSeconds();
        session.countdownRemainingSeconds(seconds);

        for (Player player : queueManager.onlinePlayers()) {
            messages.sendWithPrefix(player, "countdown.started", Map.of("seconds", Integer.toString(seconds)));
        }

        taskRegistry.runRepeating(COUNTDOWN_TASK, this::tickCountdown, 0L, 20L);
    }

    private void tickCountdown() {
        if (session.state() != GameState.COUNTDOWN) {
            taskRegistry.cancel(COUNTDOWN_TASK);
            return;
        }

        if (queueManager.onlineSize() < configService.config().minPlayers()) {
            cancelCountdown();
            return;
        }

        int remaining = session.countdownRemainingSeconds();
        if (remaining <= 0) {
            completeCountdown();
            return;
        }

        if (COUNTDOWN_SUBTITLE_SECONDS.contains(remaining)) {
            showCountdownSubtitle(remaining);
        }
        playCountdownTick(remaining);

        session.countdownRemainingSeconds(remaining - 1);
    }

    private void cancelCountdown() {
        taskRegistry.cancel(COUNTDOWN_TASK);
        session.countdownRemainingSeconds(0);
        stateController.transition(session, GameState.WAITING);

        for (Player player : queueManager.onlinePlayers()) {
            messages.sendWithPrefix(player, "countdown.cancelled", Map.of());
        }
    }

    private void completeCountdown() {
        completeCountdown(false);
    }

    private void completeCountdown(boolean ignoreMinimumPlayers) {
        taskRegistry.cancel(COUNTDOWN_TASK);
        List<UUID> participants = queueManager.onlineSnapshot();
        if (!ignoreMinimumPlayers && participants.size() < configService.config().minPlayers()) {
            cancelCountdown();
            return;
        }
        if (participants.isEmpty()) {
            stateController.transition(session, GameState.WAITING);
            session.countdownRemainingSeconds(0);
            return;
        }
        if (participants.size() > arenaManager.allArenas().size()) {
            plugin.getLogger().severe("Not enough arenas for queued players.");
            stateController.transition(session, GameState.WAITING);
            session.countdownRemainingSeconds(0);
            return;
        }

        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID participant : participants) {
            Player player = Bukkit.getPlayer(participant);
            names.put(participant, player == null ? "Unknown" : player.getName());
        }
        session.startRound(participants, arenaManager.assignArenas(participants), names);
        buildSettingsService.resetFloors(session.usedArenas());
        queueManager.clear();
        for (UUID participant : participants) {
            playerStateService.mark(participant, PlayerStatus.PARTICIPANT);
            Player player = Bukkit.getPlayer(participant);
            if (player != null) {
                player.getInventory().clear();
                if (!player.isOp()) {
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setAllowFlight(false);
                    player.setFlying(false);
                }
                assignedArena(participant).ifPresent(arena -> player.teleportAsync(arena.ownerSpawn()));
                configService.config().sounds().countdownStart().play(player);
            }
        }

        stateController.transition(session, GameState.THEME_VOTING);
        session.phaseRemainingSeconds(configService.config().timings().themeVotingSeconds());
        startThemeVoting();
    }

    private void startThemeVoting() {
        for (Player player : onlineActivePlayers()) {
            player.showTitle(Title.title(
                    messages.component("theme.voting-title"),
                    messages.component("theme.voting-subtitle"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(300))
            ));
        }
        themeVotingService.start(onlineActivePlayers(), this::onThemeSelected);
        taskRegistry.runRepeating("theme-time-sync", () ->
                session.phaseRemainingSeconds(themeVotingService.remainingSeconds()), 0L, 20L);
    }

    private void onThemeSelected(Theme theme) {
        taskRegistry.cancel("theme-time-sync");
        session.selectedTheme(theme);
        int delay = configService.config().timings().selectedThemeTitleSeconds();
        Title title = Title.title(
                messages.component("theme.selected-title"),
                messages.component("theme.selected-subtitle", Map.of("theme", theme.displayName())),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(delay), Duration.ofMillis(250))
        );
        for (Player player : onlineActivePlayers()) {
            player.showTitle(title);
        }
        taskRegistry.runLater(THEME_SELECTED_DELAY_TASK, this::startBuildingPhase, delay * 20L);
    }

    private void startBuildingPhase() {
        if (session.state() != GameState.THEME_VOTING) {
            return;
        }
        stateController.transition(session, GameState.BUILDING);
        int seconds = configService.config().timings().buildingSeconds();
        session.phaseRemainingSeconds(seconds);

        for (Player player : onlineActivePlayers()) {
            prepareBuildingPlayer(player);
            messages.sendWithPrefix(player, "building.started", Map.of("theme", themePlaceholder()));
        }

        taskRegistry.runRepeating(BUILDING_TASK, this::tickBuilding, 0L, 20L);
    }

    private void tickBuilding() {
        if (session.state() != GameState.BUILDING) {
            taskRegistry.cancel(BUILDING_TASK);
            return;
        }
        if (session.activeParticipants().isEmpty()) {
            resetAfterEmptyRound();
            return;
        }

        int remaining = session.phaseRemainingSeconds();
        for (Player player : onlineActivePlayers()) {
            messages.sendActionBar(player, "building.actionbar", Map.of("time", TimeFormatter.mmss(remaining)));
        }
        if (configService.config().timings().buildingReminderSeconds().contains(remaining)) {
            showBuildingReminder(remaining);
        }
        if (remaining > 0 && remaining <= BUILDING_FINAL_COUNTDOWN_SECONDS) {
            playBuildingFinalCountdownTick(remaining);
        }
        if (remaining <= 0) {
            finishBuildingPhase();
            return;
        }
        session.phaseRemainingSeconds(remaining - 1);
    }

    private void finishBuildingPhase() {
        if (session.state() != GameState.BUILDING) {
            return;
        }
        taskRegistry.cancel(BUILDING_TASK);
        stateController.transition(session, GameState.PRE_JUDGING);
        int delay = configService.config().timings().preJudgingSeconds();
        session.phaseRemainingSeconds(delay);
        for (Player player : onlineActivePlayers()) {
            preparePreJudgingPlayer(player);
            messages.sendWithPrefix(player, "building.ended", Map.of());
            messages.sendWithPrefix(player, "pre-judging.started", Map.of());
        }
        taskRegistry.runLater(PRE_JUDGING_DELAY_TASK, this::startJudging, delay * 20L);
    }

    private void startJudging() {
        if (session.state() != GameState.PRE_JUDGING) {
            return;
        }
        List<UUID> owners = session.eligibleBuildOwners();
        if (owners.isEmpty()) {
            finishJudging(List.of());
            return;
        }
        stateController.transition(session, GameState.JUDGING);
        for (Player player : onlineActivePlayers()) {
            messages.sendWithPrefix(player, "judging.started", Map.of());
        }
        judgingService.start(
                owners,
                configService.config().timings().judgingSecondsPerArena(),
                this::finishJudging
        );
    }

    private void finishJudging(List<RoundBuildScore> scores) {
        if (scores.isEmpty()) {
            startReset(session.usedArenas(), true);
            return;
        }
        stateController.transition(session, GameState.RESULTS);
        List<RoundPlacement> placements = scoreService.rank(scores);
        statisticsService.applyRoundPlacements(placements);
        resultsService.show(placements, onlineActivePlayers(), this::assignedArena);
        session.phaseRemainingSeconds(configService.config().timings().resultsSeconds());
        taskRegistry.runRepeating(RESULTS_TASK, this::tickResults, 20L, 20L);
    }

    private void tickResults() {
        int remaining = session.phaseRemainingSeconds() - 1;
        session.phaseRemainingSeconds(remaining);
        if (remaining <= 0) {
            taskRegistry.cancel(RESULTS_TASK);
            startReset(session.usedArenas(), true);
        }
    }

    private void startReset(Collection<Arena> arenas, boolean cleanupPlayers) {
        cancelPhaseServices();
        Collection<Arena> resetTargets = arenas.isEmpty() ? List.of() : List.copyOf(arenas);
        stateController.transition(session, GameState.RESETTING);
        session.phaseRemainingSeconds(0);

        if (cleanupPlayers) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                prepareLobbyPlayer(player);
                playerStateService.mark(player.getUniqueId(), PlayerStatus.WAITING_NEXT);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            messages.sendWithPrefix(player, "reset.started", Map.of());
        }

        arenaResetService.resetArenas(resetTargets, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                messages.sendWithPrefix(player, "reset.completed", Map.of());
            }
            startWaitingCycle();
        });
    }

    private void resetAfterEmptyRound() {
        startReset(session.usedArenas(), true);
    }

    private void cancelPhaseServices() {
        taskRegistry.cancel(COUNTDOWN_TASK);
        taskRegistry.cancel(THEME_SELECTED_DELAY_TASK);
        taskRegistry.cancel(BUILDING_TASK);
        taskRegistry.cancel(PRE_JUDGING_DELAY_TASK);
        taskRegistry.cancel(RESULTS_TASK);
        taskRegistry.cancel("theme-time-sync");
        themeVotingService.cancel();
        judgingService.cancel();
        effects.cleanupGoatEffects();
    }

    private void restartLobbyActionBarTask() {
        taskRegistry.runRepeating(
                LOBBY_ACTIONBAR_TASK,
                this::updateLobbyActionBars,
                0L,
                configService.config().tasks().lobbyActionbarIntervalTicks()
        );
    }

    private void restartMaintenanceTask() {
        taskRegistry.runRepeating(
                PLAYER_MAINTENANCE_TASK,
                () -> Bukkit.getOnlinePlayers().forEach(this::maintainPlayer),
                0L,
                configService.config().tasks().playerMaintenanceIntervalTicks()
        );
    }

    private void restartSnowEffectTask() {
        taskRegistry.runRepeating(
                SNOW_EFFECT_TASK,
                this::tickSnowEffects,
                10L,
                10L
        );
    }

    private void updateLobbyActionBars() {
        GameState state = session.state();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (state.acceptsQueueForCurrentRound() && queueManager.contains(playerId)) {
                messages.sendActionBar(player, "waiting.actionbar", Map.of(
                        "queue", Integer.toString(queueManager.onlineSize()),
                        "max", Integer.toString(configService.config().maxPlayers())
                ));
            } else if (playerStateService.isWaitingNext(playerId)) {
                messages.sendActionBar(player, "join.waiting-next-actionbar", Map.of());
            }
        }
    }

    private void showCountdownSubtitle(int seconds) {
        Component subtitle = messages.component("countdown.subtitle", Map.of("seconds", Integer.toString(seconds)));
        Title title = Title.title(
                Component.empty(),
                subtitle,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900L), Duration.ofMillis(150L))
        );
        for (Player player : queueManager.onlinePlayers()) {
            player.showTitle(title);
        }
    }

    private void showBuildingReminder(int seconds) {
        Map<String, String> placeholders = Map.of(
                "seconds", Integer.toString(seconds),
                "time", TimeFormatter.mmss(seconds)
        );
        Title title = Title.title(
                messages.component("building.reminder-title", placeholders),
                messages.component("building.reminder-subtitle", placeholders),
                Title.Times.times(Duration.ofMillis(150L), Duration.ofMillis(1300L), Duration.ofMillis(250L))
        );
        for (Player player : onlineActivePlayers()) {
            player.showTitle(title);
        }
    }

    private void playCountdownTick(int seconds) {
        SoundSetting sound = configService.config().sounds().countdown().get(seconds);
        if (sound == null) {
            return;
        }
        for (Player player : queueManager.onlinePlayers()) {
            sound.play(player);
        }
    }

    private void playBuildingFinalCountdownTick(int seconds) {
        SoundSetting sound = configService.config().sounds().buildingFinalCountdown().get(seconds);
        if (sound == null) {
            return;
        }
        for (Player player : onlineActivePlayers()) {
            sound.play(player);
        }
    }

    private void tickSnowEffects() {
        if (!configService.config().snowEffect().enabled()) {
            return;
        }
        GameState state = session.state();
        if (state == GameState.BUILDING) {
            for (Player player : onlineActivePlayers()) {
                assignedArena(player.getUniqueId())
                        .filter(buildSettingsService::isSnowing)
                        .filter(arena -> arena.moduleRegion().contains(player.getLocation()))
                        .ifPresent(arena -> effects.playSnowEffect(arena, player));
            }
            return;
        }
        if (state == GameState.JUDGING && session.currentJudgedOwner() != null) {
            Optional<Arena> arena = assignedArena(session.currentJudgedOwner()).filter(buildSettingsService::isSnowing);
            if (arena.isEmpty()) {
                return;
            }
            for (Player player : onlineActivePlayers()) {
                if (arena.get().moduleRegion().contains(player.getLocation())) {
                    effects.playSnowEffect(arena.get(), player);
                }
            }
        }
    }

    private void prepareLobbyPlayer(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        if (!player.isOp()) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        buildSettingsService.resetVisuals(player);
        maintainPlayer(player);
        player.teleportAsync(lobbyLocation(player));
    }

    private void prepareBuildingPlayer(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        if (!player.isOp()) {
            player.setGameMode(GameMode.CREATIVE);
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        maintainPlayer(player);
        buildSettingsService.giveCompass(player);
        assignedArena(player.getUniqueId()).ifPresent(arena -> {
            buildSettingsService.applyVisuals(player, arena);
            player.teleportAsync(arena.ownerSpawn());
        });
    }

    private void preparePreJudgingPlayer(Player player) {
        player.closeInventory();
        player.getInventory().clear();
        if (!player.isOp()) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
        }
        maintainPlayer(player);
    }

    private Location lobbyLocation(Player player) {
        PluginConfig config = configService.config();
        World world = Bukkit.getWorld(config.map().worldName());
        if (world == null) {
            world = fallbackWorld(player);
            plugin.getLogger().warning("World '" + config.map().worldName()
                    + "' from config.yml is not loaded. Using '" + world.getName() + "' for lobby teleport.");
        }

        PluginConfig.Point spawn = config.map().lobbySpawn();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    private World fallbackWorld(Player player) {
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().get(0);
        }
        return player.getWorld();
    }

    private List<Player> onlineActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID playerId : session.activeParticipants()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                players.add(player);
            }
        }
        return players;
    }

    private String countdownPlaceholder() {
        if (session.state() != GameState.COUNTDOWN) {
            return "-";
        }
        return Integer.toString(session.countdownRemainingSeconds());
    }
}

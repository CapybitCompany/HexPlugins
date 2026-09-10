package hexbuildbattle.judging;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.effect.BuildBattleEffects;
import hexbuildbattle.game.GameSession;
import hexbuildbattle.game.GameTaskRegistry;
import hexbuildbattle.rating.RatingDefinition;
import hexbuildbattle.rating.RatingService;
import hexbuildbattle.report.ReportService;
import hexbuildbattle.report.ReportService.ReportResult;
import hexbuildbattle.report.ReportService.ReportStatus;
import hexbuildbattle.score.RoundBuildScore;
import hexbuildbattle.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class JudgingService {

    private static final String JUDGING_TASK = "judging";

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final GameTaskRegistry taskRegistry;
    private final BuildSettingsService buildSettingsService;
    private final RatingService ratingService;
    private final ReportService reportService;
    private final BuildBattleEffects effects;
    private final GameSession session;

    private List<UUID> judgingOrder = List.of();
    private int currentIndex;
    private int remainingSeconds;
    private int secondsPerBuild;
    private Consumer<List<RoundBuildScore>> completion;

    public JudgingService(
            JavaPlugin plugin,
            MessageService messages,
            GameTaskRegistry taskRegistry,
            BuildSettingsService buildSettingsService,
            RatingService ratingService,
            ReportService reportService,
            BuildBattleEffects effects,
            GameSession session
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.taskRegistry = taskRegistry;
        this.buildSettingsService = buildSettingsService;
        this.ratingService = ratingService;
        this.reportService = reportService;
        this.effects = effects;
        this.session = session;
    }

    public void start(List<UUID> buildOwners, int secondsPerBuild, Consumer<List<RoundBuildScore>> completion) {
        cancel();
        reportService.clearRoundReports();
        List<UUID> order = new ArrayList<>(buildOwners);
        Collections.shuffle(order);
        this.judgingOrder = order;
        this.currentIndex = -1;
        this.secondsPerBuild = Math.max(1, secondsPerBuild);
        this.remainingSeconds = this.secondsPerBuild;
        this.completion = completion;
        advanceToNextBuild();
    }

    public void cancel() {
        taskRegistry.cancel(JUDGING_TASK);
        reportService.clearRoundReports();
        session.currentJudgedOwner(null);
        judgingOrder = List.of();
        currentIndex = -1;
        remainingSeconds = 0;
        secondsPerBuild = 0;
        completion = null;
    }

    public int remainingSeconds() {
        return remainingSeconds;
    }

    public void handleParticipantRemoved(UUID playerId) {
        if (playerId.equals(session.currentJudgedOwner()) && !session.isBuildEligibleForJudging(playerId)) {
            advanceToNextBuild();
        }
    }

    public boolean handleRatingUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return false;
        }
        ItemStack item = event.getItem();
        if (ratingService.isReportItem(item)) {
            if (!isClickAction(event.getAction())) {
                return false;
            }
            event.setCancelled(true);
            submitReport(event.getPlayer());
            return true;
        }

        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return false;
        }
        Optional<RatingDefinition> rating = ratingService.ratingFromItem(item);
        if (rating.isEmpty()) {
            return false;
        }
        event.setCancelled(true);
        submitRating(event.getPlayer(), rating.get());
        return true;
    }

    public void submitRating(Player voter, RatingDefinition rating) {
        UUID ownerId = session.currentJudgedOwner();
        if (ownerId == null || !session.isActiveParticipant(voter.getUniqueId())) {
            return;
        }
        if (ownerId.equals(voter.getUniqueId())) {
            messages.sendWithPrefix(voter, "judging.cannot-self-vote", Map.of());
            return;
        }

        RoundBuildScore score = session.score(ownerId).orElse(null);
        Arena arena = session.assignedArena(ownerId).orElse(null);
        if (score == null || arena == null) {
            return;
        }

        score.vote(voter.getUniqueId(), rating);
        ratingService.playRatingSound(voter, rating);
        messages.sendWithPrefix(voter, "judging.vote-cast", Map.of("rating", rating.displayName()));

        Optional<RatingDefinition> highest = ratingService.highestRating();
        if (highest.isPresent()
                && highest.get().level() == rating.level()
                && score.markGoatEffectTriggered(voter.getUniqueId())) {
            effects.playGoatEffect(arena, onlineActivePlayers());
        }
    }

    private void submitReport(Player reporter) {
        UUID ownerId = session.currentJudgedOwner();
        if (ownerId == null || !session.isActiveParticipant(reporter.getUniqueId()) || ownerId.equals(reporter.getUniqueId())) {
            return;
        }

        RoundBuildScore score = session.score(ownerId).orElse(null);
        if (score == null) {
            return;
        }

        ReportResult result = reportService.report(reporter, score, session.selectedTheme());
        if (result.status() == ReportStatus.FAILED) {
            sendReportMessage(
                    reporter,
                    "judging.report-failed",
                    "&cNie udało się zapisać raportu. Powiadom administrację.",
                    Map.of()
            );
            return;
        }

        if (result.status() == ReportStatus.DUPLICATE) {
            sendReportMessage(
                    reporter,
                    "judging.report-duplicate",
                    "&eJuż zgłosiłeś tę budowlę.",
                    Map.of("id", result.id())
            );
            return;
        }

        Map<String, String> placeholders = Map.of(
                "id", result.id(),
                "reported", score.ownerName(),
                "reporter", reporter.getName(),
                "file", result.metadataFile().getPath(),
                "schematic", result.schematicFile().getPath()
        );
        ratingService.playReportSound(reporter);
        sendReportMessage(
                reporter,
                "judging.report-created",
                "&aZgłosiłeś tę budowlę.",
                placeholders
        );
    }

    private boolean isClickAction(Action action) {
        return action == Action.RIGHT_CLICK_AIR
                || action == Action.RIGHT_CLICK_BLOCK
                || action == Action.LEFT_CLICK_AIR
                || action == Action.LEFT_CLICK_BLOCK;
    }

    private void sendReportMessage(Player player, String path, String fallback, Map<String, String> placeholders) {
        String prefix = messages.raw("prefix", "");
        String message = Text.replace(messages.raw(path, fallback), placeholders);
        player.sendMessage(Text.component(prefix + message));
    }

    private void advanceToNextBuild() {
        taskRegistry.cancel(JUDGING_TASK);
        effects.cleanupGoatEffects();
        currentIndex++;

        while (currentIndex < judgingOrder.size()
                && !session.isBuildEligibleForJudging(judgingOrder.get(currentIndex))) {
            currentIndex++;
        }

        if (currentIndex >= judgingOrder.size()) {
            Consumer<List<RoundBuildScore>> callback = completion;
            List<RoundBuildScore> scores = session.eligibleScores();
            cancel();
            if (callback != null) {
                callback.accept(scores);
            }
            return;
        }

        UUID ownerId = judgingOrder.get(currentIndex);
        session.currentJudgedOwner(ownerId);
        remainingSeconds = Math.max(1, secondsPerBuild);
        prepareCurrentBuild(ownerId);
        broadcastAuthorActionBar(ownerId);
        taskRegistry.runRepeating(JUDGING_TASK, this::tickCurrentBuild, 20L, 20L);
    }

    private void tickCurrentBuild() {
        UUID ownerId = session.currentJudgedOwner();
        if (ownerId == null || !session.isBuildEligibleForJudging(ownerId)) {
            advanceToNextBuild();
            return;
        }

        broadcastAuthorActionBar(ownerId);

        remainingSeconds--;
        session.phaseRemainingSeconds(remainingSeconds);
        if (remainingSeconds <= 0) {
            advanceToNextBuild();
        }
    }

    private void prepareCurrentBuild(UUID ownerId) {
        Arena arena = session.assignedArena(ownerId).orElse(null);
        if (arena == null) {
            return;
        }
        remainingSeconds = Math.max(1, secondsPerBuild);
        session.phaseRemainingSeconds(remainingSeconds);
        Location judgingLocation = safeJudgingLocation(arena);
        for (Player player : onlineActivePlayers()) {
            player.teleportAsync(judgingLocation);
            if (!player.isOp()) {
                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(true);
                player.setFlying(true);
            }
            buildSettingsService.applyVisuals(player, arena);
            if (player.getUniqueId().equals(ownerId)) {
                ratingService.clearRatingItems(player);
            } else {
                ratingService.giveRatingItems(player);
            }
        }
    }

    private Location safeJudgingLocation(Arena arena) {
        return safeIfClear(arena.judgingCenter())
                .or(() -> safeIfClear(overheadJudgingLocation(arena)))
                .orElseGet(() -> scanSafeJudgingLocation(arena));
    }

    private Location overheadJudgingLocation(Arena arena) {
        double x = (arena.buildRegion().minX() + arena.buildRegion().maxX()) / 2.0D + 0.5D;
        double y = Math.min(arena.moduleRegion().maxY() - 2.0D, arena.buildRegion().maxY() + 4.0D);
        double z = (arena.buildRegion().minZ() + arena.buildRegion().maxZ()) / 2.0D + 0.5D;
        return new Location(
                arena.buildRegion().world(),
                x,
                y,
                z,
                arena.judgingCenter().getYaw(),
                arena.judgingCenter().getPitch()
        );
    }

    private Location scanSafeJudgingLocation(Arena arena) {
        World world = arena.buildRegion().world();
        int minY = Math.max(world.getMinHeight(), arena.floorRegion().maxY() + 1);
        int maxY = Math.min(world.getMaxHeight() - 2, arena.moduleRegion().maxY() - 1);
        int centerX = (arena.buildRegion().minX() + arena.buildRegion().maxX()) / 2;
        int centerZ = (arena.buildRegion().minZ() + arena.buildRegion().maxZ()) / 2;
        int maxRadius = Math.max(
                arena.buildRegion().maxX() - arena.buildRegion().minX(),
                arena.buildRegion().maxZ() - arena.buildRegion().minZ()
        ) / 2;

        for (int y = maxY; y >= minY; y--) {
            for (int radius = 0; radius <= maxRadius; radius++) {
                Optional<Location> safe = scanRing(world, arena, centerX, centerZ, y, radius);
                if (safe.isPresent()) {
                    return safe.get();
                }
            }
        }

        return overheadJudgingLocation(arena);
    }

    private Optional<Location> scanRing(World world, Arena arena, int centerX, int centerZ, int y, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                    continue;
                }
                int x = centerX + dx;
                int z = centerZ + dz;
                if (x < arena.buildRegion().minX() || x > arena.buildRegion().maxX()
                        || z < arena.buildRegion().minZ() || z > arena.buildRegion().maxZ()) {
                    continue;
                }
                Optional<Location> safe = safeIfClear(new Location(
                        world,
                        x + 0.5D,
                        y,
                        z + 0.5D,
                        arena.judgingCenter().getYaw(),
                        arena.judgingCenter().getPitch()
                ));
                if (safe.isPresent()) {
                    return safe;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Location> safeIfClear(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        if (!feet.isPassable() || !head.isPassable()) {
            return Optional.empty();
        }
        return Optional.of(new Location(
                location.getWorld(),
                location.getBlockX() + 0.5D,
                location.getY(),
                location.getBlockZ() + 0.5D,
                location.getYaw(),
                location.getPitch()
        ));
    }

    private void broadcastAuthorActionBar(UUID ownerId) {
        String ownerName = session.score(ownerId)
                .map(RoundBuildScore::ownerName)
                .orElse("?");
        for (Player player : onlineActivePlayers()) {
            messages.sendActionBar(player, "judging.actionbar", Map.of("name", ownerName));
        }
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
}

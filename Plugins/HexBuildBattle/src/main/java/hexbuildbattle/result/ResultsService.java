package hexbuildbattle.result;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.effect.BuildBattleEffects;
import hexbuildbattle.score.RoundPlacement;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class ResultsService {

    private final MessageService messages;
    private final BuildSettingsService buildSettingsService;
    private final BuildBattleEffects effects;

    public ResultsService(
            MessageService messages,
            BuildSettingsService buildSettingsService,
            BuildBattleEffects effects
    ) {
        this.messages = messages;
        this.buildSettingsService = buildSettingsService;
        this.effects = effects;
    }

    public void show(
            List<RoundPlacement> placements,
            List<Player> activePlayers,
            Function<UUID, Optional<Arena>> arenaResolver
    ) {
        if (placements.isEmpty()) {
            for (Player player : activePlayers) {
                messages.sendWithPrefix(player, "judging.no-builds", Map.of());
            }
            return;
        }

        RoundPlacement winner = placements.get(0);
        Optional<Arena> winnerArena = arenaResolver.apply(winner.ownerId());
        if (winnerArena.isPresent()) {
            for (Player player : activePlayers) {
                player.teleportAsync(winnerArena.get().judgingCenter());
                buildSettingsService.applyVisuals(player, winnerArena.get());
            }
            effects.playResultsEffect(winnerArena.get(), activePlayers);
        }

        for (Player player : activePlayers) {
            player.sendMessage(Component.empty());
            player.sendMessage(messages.component("results.title"));
            placements.stream().limit(7).forEach(placement -> messages.send(player, resultLinePath(placement), Map.of(
                    "place", Integer.toString(placement.place()),
                    "name", placement.ownerName(),
                    "score", scoreText(placement)
            )));

            messages.send(player, "results.separator", Map.of());
            RoundPlacement own = placementFor(player.getUniqueId(), placements);
            if (own == null) {
                messages.send(player, "results.no-place", Map.of());
            } else {
                messages.send(player, "results.own-place", Map.of(
                        "place", Integer.toString(own.place())
                ));
                messages.send(player, "results.ranking-points", Map.of(
                        "points", Integer.toString(own.rankingPoints())
                ));
            }
            player.sendMessage(Component.empty());
        }

        Bukkit.getConsoleSender().sendMessage(messages.component("results.title"));
        placements.stream().limit(7).forEach(placement -> messages.send(Bukkit.getConsoleSender(), resultLinePath(placement), Map.of(
                "place", Integer.toString(placement.place()),
                "name", placement.ownerName(),
                "score", scoreText(placement)
        )));
    }

    private String resultLinePath(RoundPlacement placement) {
        String path = switch (placement.place()) {
            case 1 -> "results.line-first";
            case 2 -> "results.line-second";
            case 3 -> "results.line-third";
            default -> "results.line";
        };
        if (!"results.line".equals(path) && messages.raw(path).isBlank()) {
            return "results.line";
        }
        return path;
    }

    private String scoreText(RoundPlacement placement) {
        return Integer.toString(placement.totalPoints());
    }

    private RoundPlacement placementFor(UUID playerId, List<RoundPlacement> placements) {
        for (RoundPlacement placement : placements) {
            if (placement.ownerId().equals(playerId)) {
                return placement;
            }
        }
        return null;
    }
}

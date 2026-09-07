package hex.parkour.config;

import hex.parkour.model.LocationSpec;
import hex.parkour.model.ParkourArena;

import java.util.List;
import java.util.Map;

public record ParkourConfig(
        String worldName,
        boolean autoJoinWorld,
        String returnWorldName,
        LocationSpec lobbySpawn,
        BarConfig lobbyBossBar,
        ItemConfig lobbyVisibilityItem,
        ItemConfig lobbyLeaveItem,
        ItemConfig arenaResetItem,
        ItemConfig arenaVisibilityItem,
        ItemConfig arenaLobbyItem,
        Map<String, String> messages,
        boolean actionbarEnabled,
        String actionbarFormat,
        String actionbarNotStartedFormat,
        PlaceholderConfig placeholders,
        String timerFormat,
        long portalCooldownTicks,
        long resetCooldownTicks,
        long visibilityRefreshTicks,
        SoundConfig checkpointSound,
        SoundConfig moneySound,
        SoundConfig startSound,
        SoundConfig finishSound,
        SoundConfig resetSound,
        ParticleConfig checkpointParticle,
        ParticleConfig moneyParticle,
        String economyFallbackCommand,
        String economyCurrency,
        String economyReason,
        FinishRewardConfig finishReward,
        Map<String, ParkourArena> arenas,
        List<String> errors
) {
    public boolean valid() {
        return errors.isEmpty() && worldName != null && !worldName.isBlank() && lobbySpawn != null && !arenas.isEmpty();
    }

    public String message(String key, String fallback) {
        return messages.getOrDefault(key, fallback);
    }
}

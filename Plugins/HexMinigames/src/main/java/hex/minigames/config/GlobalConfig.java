package hex.minigames.config;

import hex.minigames.model.LocationSpec;

import java.util.List;

public record GlobalConfig(
        String worldName,
        int maxPlayers,
        int developmentMinimumPlayers,
        int gamesPerSeries,
        int roundCountdownSeconds,
        int defaultRoundSeconds,
        int roundResultsSeconds,
        int intermissionSeconds,
        int seriesResultsSeconds,
        String storageType,
        String sqliteFile,
        boolean restoreOnJoin,
        boolean ghostAllowFlight,
        PregameConfig pregame,
        SeriesConfig series,
        boolean debugEnabled,
        boolean debugLogStateTransitions,
        int debugGameDurationSeconds,
        LocationSpec debugSpawn,
        ConfiguredSound roundEndSound,
        List<String> errors
) {
    public GlobalConfig {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}

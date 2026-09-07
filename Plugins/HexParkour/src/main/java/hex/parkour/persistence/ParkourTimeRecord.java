package hex.parkour.persistence;

import java.util.UUID;

public record ParkourTimeRecord(
        UUID playerId,
        String playerName,
        String arenaId,
        long bestTimeMillis,
        long lastTimeMillis,
        int finishes,
        long updatedAtMillis,
        boolean finishRewardClaimed
) {
    public ParkourTimeRecord withFinish(long timeMillis, String latestName, long nowMillis) {
        long newBest = bestTimeMillis < 0L ? timeMillis : Math.min(bestTimeMillis, timeMillis);
        return new ParkourTimeRecord(
                playerId,
                latestName,
                arenaId,
                newBest,
                timeMillis,
                finishes + 1,
                nowMillis,
                finishRewardClaimed
        );
    }

    public ParkourTimeRecord withFinishRewardClaimed(String latestName, long nowMillis) {
        return new ParkourTimeRecord(
                playerId,
                latestName,
                arenaId,
                bestTimeMillis,
                lastTimeMillis,
                finishes,
                nowMillis,
                true
        );
    }
}

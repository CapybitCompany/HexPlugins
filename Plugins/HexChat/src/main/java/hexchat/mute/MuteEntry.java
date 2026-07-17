package hexchat.mute;

import java.util.Objects;
import java.util.UUID;

/**
 * Pojedynczy wpis wyciszenia gracza.
 *
 * @param playerId          UUID wyciszonego gracza
 * @param playerName        ostatnia znana nazwa (do wyświetlania)
 * @param untilEpochMillis  moment wygaśnięcia w epoch millis; {@code 0} = permanentne
 * @param reason            powód wyciszenia
 * @param createdAtEpochMillis moment nałożenia wyciszenia
 */
public record MuteEntry(
        UUID playerId,
        String playerName,
        long untilEpochMillis,
        String reason,
        long createdAtEpochMillis
) {
    public MuteEntry {
        playerId = Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        reason = Objects.requireNonNull(reason, "reason");
    }

    public boolean permanent() {
        return untilEpochMillis <= 0L;
    }

    public boolean isExpiredAt(long nowMillis) {
        return !permanent() && untilEpochMillis <= nowMillis;
    }

    public long remainingMillisAt(long nowMillis) {
        if (permanent()) {
            return 0L;
        }
        return Math.max(0L, untilEpochMillis - nowMillis);
    }
}

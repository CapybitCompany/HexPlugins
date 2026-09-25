package hexposterunki.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic top-N ranking inside the town that finally controlled the outpost.
 *
 * <p>Order: more kills first, then the earlier moment the kill count was reached, then the
 * player UUID as a stable final tie-breaker.
 */
public final class RankingCalculator {

    /** Shared ordering so ranking and rank lookup can never drift apart. */
    public static final Comparator<KillEntry> ORDER = Comparator
            .comparingInt(KillEntry::kills).reversed()
            .thenComparingLong(KillEntry::achievedAtMillis)
            .thenComparing(entry -> entry.playerId().toString());

    private RankingCalculator() {
    }

    /**
     * @param controllingTown only members of this town are eligible; {@code null} yields an empty list
     * @param minKills        players below this threshold are excluded entirely
     * @param limit           maximum number of places (5 in the default configuration)
     */
    public static List<KillEntry> top(Collection<KillEntry> entries, UUID controllingTown, int minKills, int limit) {
        return eligible(entries, controllingTown, minKills).stream()
                .limit(Math.max(0, limit))
                .toList();
    }

    /**
     * @return the 1-based rank of the player inside the eligible ranking, or 0 when the player
     * is not eligible (no town match or below the minimum kills).
     */
    public static int rankOf(Collection<KillEntry> entries, UUID controllingTown, int minKills, UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        List<KillEntry> sorted = eligible(entries, controllingTown, minKills);
        for (int i = 0; i < sorted.size(); i++) {
            if (playerId.equals(sorted.get(i).playerId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static List<KillEntry> eligible(Collection<KillEntry> entries, UUID controllingTown, int minKills) {
        if (entries == null || entries.isEmpty() || controllingTown == null) {
            return List.of();
        }
        int threshold = Math.max(0, minKills);
        List<KillEntry> eligible = new ArrayList<>();
        for (KillEntry entry : entries) {
            if (entry == null || entry.kills() < threshold || entry.kills() <= 0) {
                continue;
            }
            if (!Objects.equals(controllingTown, entry.townId())) {
                continue;
            }
            eligible.add(entry);
        }
        eligible.sort(ORDER);
        return eligible;
    }
}

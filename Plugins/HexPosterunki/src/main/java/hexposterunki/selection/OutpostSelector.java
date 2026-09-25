package hexposterunki.selection;

import hexposterunki.config.OutpostDefinition;
import hexposterunki.util.RandomSource;
import hexposterunki.util.Weighted;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Picks the next fortress. Pure logic: weights decide the odds, and the previously used location
 * is excluded whenever another usable candidate exists, so the same fortress is not run twice in
 * a row.
 */
public final class OutpostSelector {

    /**
     * @param lastOutpostId location used by the previous run, or null on a fresh server
     * @param avoidRepeat   when true the previous location is skipped if anything else is available
     */
    public Optional<OutpostDefinition> select(Collection<OutpostDefinition> candidates,
                                              String lastOutpostId,
                                              boolean avoidRepeat,
                                              RandomSource random) {
        List<OutpostDefinition> usable = new ArrayList<>();
        for (OutpostDefinition candidate : candidates == null ? List.<OutpostDefinition>of() : candidates) {
            if (candidate != null && candidate.weight() > 0) {
                usable.add(candidate);
            }
        }
        if (usable.isEmpty()) {
            return Optional.empty();
        }

        List<OutpostDefinition> pool = usable;
        if (avoidRepeat && lastOutpostId != null) {
            List<OutpostDefinition> withoutLast = usable.stream()
                    .filter(candidate -> !Objects.equals(lastOutpostId, candidate.id()))
                    .toList();
            // Only honour the "no immediate repeat" rule while an alternative actually exists.
            if (!withoutLast.isEmpty()) {
                pool = withoutLast;
            }
        }
        return Weighted.pick(pool, OutpostDefinition::weight, random);
    }
}

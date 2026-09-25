package hexposterunki.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which town controls the outpost, given who is currently inside the region.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>A foreign town must first push the controller out: as long as at least one living, online
 *       member of the controlling town is inside, control does not move.</li>
 *   <li>Once no controller member remains, an already present foreign town takes over.</li>
 *   <li>With several candidates the winner is the town whose currently present player entered the
 *       region first; equal timestamps fall back to the player UUID so the result is stable.</li>
 * </ol>
 */
public final class ControlResolver {

    private static final Comparator<Presence> ENTRY_ORDER = Comparator
            .comparingLong(Presence::enteredAtMillis)
            .thenComparing(presence -> presence.playerId().toString());

    private ControlResolver() {
    }

    /**
     * @param currentTown the controlling town, or null when the outpost is unclaimed
     * @param present     every eligible (living, online, town-owning) player inside the region
     * @return the town that should control the outpost now, or empty when the region holds no
     * eligible player at all
     */
    public static Optional<UUID> resolve(UUID currentTown, Collection<Presence> present) {
        if (present == null || present.isEmpty()) {
            return Optional.empty();
        }
        if (currentTown != null) {
            for (Presence presence : present) {
                if (Objects.equals(currentTown, presence.townId())) {
                    return Optional.of(currentTown);
                }
            }
        }
        return present.stream().min(ENTRY_ORDER).map(Presence::townId);
    }

    /** True when the resolution would move control to a different town than the current one. */
    public static boolean isTakeover(UUID currentTown, Collection<Presence> present) {
        Optional<UUID> resolved = resolve(currentTown, present);
        return resolved.isPresent() && !Objects.equals(currentTown, resolved.get());
    }

    /** Convenience view used by the engine when logging or announcing a takeover. */
    public static List<Presence> ordered(Collection<Presence> present) {
        return present == null ? List.of() : present.stream().sorted(ENTRY_ORDER).toList();
    }
}

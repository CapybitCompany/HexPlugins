package hexposterunki.config;

import java.util.List;
import java.util.Objects;

/**
 * One configured mob wave.
 *
 * <p>Version 1 spawns exactly {@code count} mobs per group; the amount is not scaled by player
 * count. The engine routes every count through {@link hexposterunki.engine.WaveScaling} so a
 * scaling strategy can be added later without touching the wave model.
 */
public record WaveDefinition(int id, List<Group> groups) {

    public WaveDefinition {
        Objects.requireNonNull(groups, "groups");
        groups = List.copyOf(groups);
    }

    public int totalMobs() {
        return groups.stream().mapToInt(Group::count).sum();
    }

    /**
     * @param mobId       HexCustomMobs template id
     * @param count       base amount for this wave
     * @param spawnPoints allowed spawn point names of the outpost; empty means "any point"
     */
    public record Group(String mobId, int count, List<String> spawnPoints) {

        public Group {
            Objects.requireNonNull(mobId, "mobId");
            count = Math.max(0, count);
            spawnPoints = spawnPoints == null ? List.of() : List.copyOf(spawnPoints);
        }
    }
}

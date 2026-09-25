package hexposterunki.region;

import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Location lookup over all protected outpost regions. Protection applies permanently, whether or not
 * an encounter is currently running there.
 *
 * <p>Two sources feed the index:
 * <ul>
 *   <li>the configured catalog, replaced on every successful reload;</li>
 *   <li>the <b>pinned active definition</b> - the frozen geometry the running encounter uses. A
 *       reload that moves or removes that outpost must not strip protection from the place where the
 *       fight is still happening, so the pinned geometry stays protected until the reset finished
 *       and the engine releases it.</li>
 * </ul>
 * The pinned definition wins lookups, so loot containers and ownership checks always resolve
 * against the geometry the run was started with.
 */
public final class RegionIndex {

    private volatile List<OutpostDefinition> outposts = List.of();
    private volatile OutpostDefinition active;

    public void rebuild(OutpostCatalog catalog) {
        this.outposts = catalog == null ? List.of() : catalog.all();
    }

    /** Keeps this exact geometry protected, independent of later catalog changes. */
    public void pinActive(OutpostDefinition definition) {
        this.active = Objects.requireNonNull(definition, "definition");
    }

    public void releaseActive() {
        this.active = null;
    }

    public Optional<OutpostDefinition> active() {
        return Optional.ofNullable(active);
    }

    public Optional<OutpostDefinition> at(Location location) {
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        String world = location.getWorld().getName();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        OutpostDefinition pinned = active;
        if (pinned != null && pinned.region().contains(world, x, y, z)) {
            return Optional.of(pinned);
        }
        for (OutpostDefinition outpost : outposts) {
            if (outpost.region().contains(world, x, y, z)) {
                return Optional.of(outpost);
            }
        }
        return Optional.empty();
    }

    public Optional<OutpostDefinition> at(Block block) {
        return block == null ? Optional.empty() : at(block.getLocation());
    }

    public boolean isProtected(Location location) {
        return at(location).isPresent();
    }

    public boolean isProtected(Block block) {
        return at(block).isPresent();
    }

    /**
     * True when the two positions are not inside the same region (used for piston/fluid crossing).
     *
     * <p>Regions are compared by their full definition, not by id: while a moved outpost is still
     * running, its old and new geometry share an id but are different regions.
     */
    public boolean crossesBoundary(Location from, Location to) {
        Optional<OutpostDefinition> source = at(from);
        Optional<OutpostDefinition> target = at(to);
        if (source.isEmpty() && target.isEmpty()) {
            return false;
        }
        return !source.equals(target);
    }

    /** Every protected region: the configured ones plus the pinned active geometry if it differs. */
    public List<OutpostDefinition> all() {
        OutpostDefinition pinned = active;
        if (pinned == null || outposts.contains(pinned)) {
            return outposts;
        }
        List<OutpostDefinition> all = new ArrayList<>(outposts.size() + 1);
        all.add(pinned);
        all.addAll(outposts);
        return List.copyOf(all);
    }
}

package hex.core.api.region;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;

public interface RegionService {

    Optional<Region> find(RegionKey key);

    default Region get(RegionKey key) {
        return find(key).orElseThrow(() -> new java.util.NoSuchElementException("Region not found: " + key));
    }

    void upsert(Region region);

    boolean delete(RegionKey key);

    List<Region> listAll();

    List<Region> listNamespace(String namespace);

    default boolean contains(RegionKey key, Location loc) {
        return find(key).map(r -> r.contains(loc)).orElse(false);
    }

    void reload();

    void save();
}

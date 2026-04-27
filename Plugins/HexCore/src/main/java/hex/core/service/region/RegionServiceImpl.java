package hex.core.service.region;

import hex.core.api.region.Region;
import hex.core.api.region.RegionKey;
import hex.core.api.region.RegionService;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionServiceImpl implements RegionService {

    private final RegionStorageYaml storage;
    private final Map<RegionKey, Region> regions = new ConcurrentHashMap<>();

    public RegionServiceImpl(File regionsFile) {
        this.storage = new RegionStorageYaml(regionsFile);
        reload();
    }

    @Override
    public Optional<Region> find(RegionKey key) {
        return Optional.ofNullable(regions.get(key));
    }

    @Override
    public void upsert(Region region) {
        regions.put(region.key(), region);
    }

    @Override
    public boolean delete(RegionKey key) {
        return regions.remove(key) != null;
    }

    @Override
    public List<Region> listAll() {
        return regions.values().stream()
                .sorted(Comparator.comparing(r -> r.key().toString()))
                .toList();
    }

    @Override
    public List<Region> listNamespace(String namespace) {
        String ns = namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
        return regions.values().stream()
                .filter(r -> r.key().namespace().equals(ns))
                .sorted(Comparator.comparing(r -> r.key().toString()))
                .toList();
    }

    @Override
    public void reload() {
        regions.clear();
        regions.putAll(storage.load());
    }

    @Override
    public void save() {
        storage.save(new HashMap<>(regions));
    }
}

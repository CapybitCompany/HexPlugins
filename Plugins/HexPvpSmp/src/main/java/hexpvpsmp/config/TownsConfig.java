package hexpvpsmp.config;

import hexpvpsmp.region.ProtectedRegion;

import java.util.List;

public record TownsConfig(
        Provider provider,
        List<ProtectedRegion> regions
) {
    public TownsConfig {
        provider = provider == null ? Provider.CONFIG : provider;
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    public enum Provider {
        CONFIG,
        WORLDGUARD
    }
}

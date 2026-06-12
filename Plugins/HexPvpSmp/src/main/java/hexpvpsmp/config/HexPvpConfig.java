package hexpvpsmp.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record HexPvpConfig(
        boolean enabled,
        boolean debug,
        CombatConfig combat,
        SafezoneConfig safezones,
        Map<String, WorldConfig> worlds,
        TownsConfig towns
) {
    public HexPvpConfig {
        combat = Objects.requireNonNull(combat, "combat");
        safezones = Objects.requireNonNull(safezones, "safezones");
        towns = Objects.requireNonNull(towns, "towns");
        worlds = worlds == null ? Map.of() : Map.copyOf(worlds);
    }

    public Optional<WorldConfig> world(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(worlds.get(name.toLowerCase(Locale.ROOT)));
    }
}

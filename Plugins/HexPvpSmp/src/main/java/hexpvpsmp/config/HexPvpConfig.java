package hexpvpsmp.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record HexPvpConfig(
        boolean enabled,
        boolean debug,
        CombatConfig combat,
        SafezoneConfig safezones,
        ProtectionConfig protection,
        MessagesConfig messages,
        Map<String, WorldConfig> worlds,
        Set<String> excludedWorlds
) {
    public HexPvpConfig {
        combat = Objects.requireNonNull(combat, "combat");
        safezones = Objects.requireNonNull(safezones, "safezones");
        protection = protection == null ? ProtectionConfig.defaults() : protection;
        messages = messages == null ? MessagesConfig.defaults() : messages;
        worlds = worlds == null ? Map.of() : Map.copyOf(worlds);
        excludedWorlds = (excludedWorlds == null ? Set.of("Hex_Minigames", "HexMinigames") : excludedWorlds)
                .stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public HexPvpConfig(boolean enabled, boolean debug, CombatConfig combat, SafezoneConfig safezones,
                        ProtectionConfig protection, MessagesConfig messages, Map<String, WorldConfig> worlds) {
        this(enabled, debug, combat, safezones, protection, messages, worlds, null);
    }

    public boolean excludesWorld(String name) {
        return name != null && excludedWorlds.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean excludes(org.bukkit.Location location) {
        return location != null && location.getWorld() != null && excludesWorld(location.getWorld().getName());
    }

    public Optional<WorldConfig> world(String name) {
        if (name == null || excludesWorld(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(worlds.get(name.toLowerCase(Locale.ROOT)));
    }
}

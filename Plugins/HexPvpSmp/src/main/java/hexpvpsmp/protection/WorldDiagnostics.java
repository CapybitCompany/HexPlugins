package hexpvpsmp.protection;

import hexpvpsmp.config.WorldConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure configuration/world consistency checks. Kept dependency-free of any live
 * server (only Bukkit-agnostic {@link WorldConfig} + plain world names) so it is
 * unit-testable and never loads a chunk.
 *
 * <p>The most common live misconfiguration is a world-name mismatch: the config
 * ships the world key {@code world}, but the actual server world is named
 * differently (e.g. {@code smp}). The region provider then matches nothing, so
 * spawn protection and public chests silently do nothing. This produces a clear
 * console warning for that case.
 */
public final class WorldDiagnostics {

    private WorldDiagnostics() {
    }

    /**
     * Warnings for every enabled configured world whose name is not among the
     * loaded server worlds (compared case-insensitively). Empty when everything
     * lines up.
     *
     * @param configuredWorlds config worlds keyed by their (lowercased) name
     * @param loadedWorldNames names of the worlds Bukkit currently has loaded
     */
    public static List<String> findConfiguredWorldsNotLoaded(
            Map<String, WorldConfig> configuredWorlds, Collection<String> loadedWorldNames) {
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
            return List.of();
        }
        Set<String> loadedLower = new LinkedHashSet<>();
        Set<String> loadedDisplay = new LinkedHashSet<>();
        if (loadedWorldNames != null) {
            for (String name : loadedWorldNames) {
                if (name != null) {
                    loadedLower.add(name.toLowerCase(Locale.ROOT));
                    loadedDisplay.add(name);
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        for (WorldConfig world : configuredWorlds.values()) {
            if (world == null || !world.enabled()) {
                continue;
            }
            if (!loadedLower.contains(world.world())) {
                warnings.add("Configured world '" + world.world() + "' is enabled in HexPvpSmp but no loaded "
                        + "server world has that name. Its spawn/no-build protection AND its public chests will "
                        + "NOT apply. Loaded worlds: " + describe(loadedDisplay) + ". Fix the world name in "
                        + "config.yml so it matches the real world.");
            }
        }
        return warnings;
    }

    private static String describe(Set<String> names) {
        if (names.isEmpty()) {
            return "(none)";
        }
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

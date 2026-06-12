package hexpvpsmp.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record CombatConfig(
        int durationSeconds,
        boolean actionbarEnabled,
        int actionbarUpdateTicks,
        Set<String> allowedCommands,
        CombatLog combatLog
) {
    public CombatConfig {
        durationSeconds = Math.max(1, durationSeconds);
        actionbarUpdateTicks = Math.max(1, actionbarUpdateTicks);
        allowedCommands = normalizeCommands(allowedCommands);
        combatLog = combatLog == null ? CombatLog.disabled() : combatLog;
    }

    public boolean isCommandAllowed(String label) {
        if (label == null || label.isEmpty()) {
            return true;
        }
        return allowedCommands.contains(label.toLowerCase(Locale.ROOT));
    }

    public int durationTicks() {
        return durationSeconds * 20;
    }

    private static Set<String> normalizeCommands(Set<String> input) {
        if (input == null || input.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : input) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            normalized.add(trimmed);
        }
        return Set.copyOf(normalized);
    }

    public static CombatConfig fromList(int durationSeconds,
                                        boolean actionbarEnabled,
                                        int actionbarUpdateTicks,
                                        List<String> allowedCommands,
                                        CombatLog combatLog) {
        return new CombatConfig(durationSeconds, actionbarEnabled, actionbarUpdateTicks,
                allowedCommands == null ? Set.of() : new LinkedHashSet<>(allowedCommands), combatLog);
    }

    /**
     * Manual death-penalty policy executed when a combat-tagged player quits.
     * Distinct from the vanilla {@code PlayerDeathEvent} flow — we never call
     * {@link org.bukkit.entity.Player#damage(double)} or {@code setHealth(0)}
     * inside {@code PlayerQuitEvent}, to avoid timing races and double drops.
     *
     * <ul>
     *   <li>{@code enabled=false}: only the combat tag and cooldowns are cleared.</li>
     *   <li>{@code enabled=true}: drop + clear are gated by {@link #dropInventory()}
     *       and {@link #dropExp()}.</li>
     * </ul>
     */
    public record CombatLog(
            boolean enabled,
            boolean dropInventory,
            boolean dropExp,
            String broadcast
    ) {
        public CombatLog {
            broadcast = broadcast == null ? "" : broadcast;
        }

        public static CombatLog disabled() {
            return new CombatLog(false, false, false, "");
        }
    }
}

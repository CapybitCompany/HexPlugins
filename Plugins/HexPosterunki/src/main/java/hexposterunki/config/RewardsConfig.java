package hexposterunki.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Optional top-5 rewards. Disabled by default: normal HexCustomMobs drops and the native
 * STORMBOSSY boss rewards are untouched by this plugin.
 *
 * @param places              how many places are rewarded (1 = best)
 * @param maxDeliveryAttempts how often a provably-not-executed delivery is retried before the claim
 *                            is parked for an administrator
 * @param rewards             place number to its reward bundle
 */
public record RewardsConfig(boolean enabled, int places, int maxDeliveryAttempts,
                            Map<Integer, PlaceReward> rewards) {

    public RewardsConfig {
        places = Math.max(0, places);
        maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
        rewards = rewards == null ? Map.of() : Map.copyOf(new TreeMap<>(rewards));
    }

    public static RewardsConfig disabled() {
        return new RewardsConfig(false, 5, 3, Map.of());
    }

    /**
     * @param items    vanilla item grants
     * @param commands console commands; {@code <player>} is replaced with the player name
     */
    public record PlaceReward(List<ItemReward> items, List<String> commands) {

        public PlaceReward {
            items = items == null ? List.of() : List.copyOf(items);
            commands = commands == null ? List.of() : List.copyOf(commands);
        }

        public boolean isEmpty() {
            return items.isEmpty() && commands.isEmpty();
        }
    }

    /**
     * @param material vanilla {@code Material} name, resolved at execution time so the pure
     *                 configuration model stays Bukkit-free
     */
    public record ItemReward(String material, int amount, String name, List<String> lore) {

        public ItemReward {
            Objects.requireNonNull(material, "material");
            amount = Math.max(1, amount);
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }
}

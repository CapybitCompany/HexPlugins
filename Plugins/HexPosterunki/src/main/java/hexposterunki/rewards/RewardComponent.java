package hexposterunki.rewards;

import java.util.List;
import java.util.Objects;

/**
 * One deliverable part of a reward. Components are executed in a stable order and counted, so a
 * delivery that broke halfway can continue with the remaining ones.
 */
public record RewardComponent(Type type, String value, int amount, String displayName, List<String> lore) {

    public RewardComponent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        amount = Math.max(1, amount);
        lore = lore == null ? List.of() : List.copyOf(lore);
    }

    public static RewardComponent item(String material, int amount, String displayName, List<String> lore) {
        return new RewardComponent(Type.ITEM, material, amount, displayName, lore);
    }

    public static RewardComponent command(String command) {
        return new RewardComponent(Type.COMMAND, command, 1, null, List.of());
    }

    public enum Type {
        ITEM,
        COMMAND
    }
}

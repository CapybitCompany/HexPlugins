package hexposterunki.rewards;

import hexposterunki.config.RewardsConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The concrete, frozen content of one reward claim.
 *
 * <p>Serialised with Bukkit's own YAML so the stored claim is readable by an administrator and
 * needs no extra dependency. Once a claim exists, editing {@code config.yml} no longer changes it.
 */
public record RewardPayload(List<RewardComponent> components) {

    public RewardPayload {
        components = components == null ? List.of() : List.copyOf(components);
    }

    public static RewardPayload empty() {
        return new RewardPayload(List.of());
    }

    public boolean isEmpty() {
        return components.isEmpty();
    }

    public int size() {
        return components.size();
    }

    /** Freezes a configured place reward into a payload: items first, then commands. */
    public static RewardPayload from(RewardsConfig.PlaceReward reward) {
        Objects.requireNonNull(reward, "reward");
        List<RewardComponent> components = new ArrayList<>();
        for (RewardsConfig.ItemReward item : reward.items()) {
            components.add(RewardComponent.item(item.material(), item.amount(), item.name(), item.lore()));
        }
        for (String command : reward.commands()) {
            if (command != null && !command.isBlank()) {
                components.add(RewardComponent.command(command));
            }
        }
        return new RewardPayload(components);
    }

    public String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Object> raw = new ArrayList<>(components.size());
        for (RewardComponent component : components) {
            YamlConfiguration entry = new YamlConfiguration();
            entry.set("type", component.type().name());
            entry.set("value", component.value());
            entry.set("amount", component.amount());
            if (component.displayName() != null) {
                entry.set("name", component.displayName());
            }
            if (!component.lore().isEmpty()) {
                entry.set("lore", component.lore());
            }
            raw.add(entry.getValues(false));
        }
        yaml.set("components", raw);
        return yaml.saveToString();
    }

    public static RewardPayload deserialize(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return empty();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(serialized);
        } catch (Exception exception) {
            return empty();
        }
        List<RewardComponent> components = new ArrayList<>();
        for (Object raw : yaml.getList("components", List.of())) {
            ConfigurationSection section = toSection(raw);
            if (section == null) {
                continue;
            }
            String type = section.getString("type", "");
            String value = section.getString("value");
            if (value == null || value.isBlank()) {
                continue;
            }
            int amount = section.getInt("amount", 1);
            if ("COMMAND".equalsIgnoreCase(type)) {
                components.add(RewardComponent.command(value));
            } else {
                components.add(RewardComponent.item(value.toUpperCase(Locale.ROOT), amount,
                        section.getString("name"), section.getStringList("lore")));
            }
        }
        return new RewardPayload(components);
    }

    private static ConfigurationSection toSection(Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return section;
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            YamlConfiguration holder = new YamlConfiguration();
            map.forEach((key, value) -> holder.set(String.valueOf(key), value));
            return holder;
        }
        return null;
    }
}

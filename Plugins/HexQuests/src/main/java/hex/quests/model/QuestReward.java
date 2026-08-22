package hex.quests.model;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record QuestReward(
        BigDecimal money,
        int experience,
        List<ItemDefinition> items,
        List<String> consoleCommands
) {
    public static QuestReward fromConfig(ConfigurationSection section) {
        if (section == null) return new QuestReward(BigDecimal.ZERO, 0, List.of(), List.of());
        BigDecimal money;
        try { money = new BigDecimal(section.getString("money", "0")); }
        catch (NumberFormatException ignored) { money = BigDecimal.ZERO; }
        int experience = Math.max(0, section.getInt("xp", 0));
        List<ItemDefinition> items = new ArrayList<>();
        ConfigurationSection itemRoot = section.getConfigurationSection("items");
        if (itemRoot != null) {
            for (String key : itemRoot.getKeys(false)) {
                ConfigurationSection item = itemRoot.getConfigurationSection(key);
                if (item != null) items.add(ItemDefinition.fromConfig(item, org.bukkit.Material.STONE));
            }
        }
        return new QuestReward(money.max(BigDecimal.ZERO), experience, List.copyOf(items),
                List.copyOf(section.getStringList("commands")));
    }
}

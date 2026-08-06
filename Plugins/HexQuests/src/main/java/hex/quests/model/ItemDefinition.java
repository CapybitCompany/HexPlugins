package hex.quests.model;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public record ItemDefinition(
        Material material,
        int amount,
        String customId,
        String displayName,
        List<String> lore,
        Integer customModelData
) {
    public static ItemDefinition fromConfig(ConfigurationSection section, Material fallback) {
        if (section == null) {
            return new ItemDefinition(fallback, 1, "", "", List.of(), null);
        }
        Material material = Material.matchMaterial(section.getString("material", fallback.name()));
        if (material == null) material = fallback;
        int amount = Math.max(1, section.getInt("amount", 1));
        String customId = section.getString("custom-id", "");
        String displayName = section.getString("name", "");
        List<String> lore = new ArrayList<>(section.getStringList("lore"));
        Integer customModelData = section.contains("custom-model-data")
                ? section.getInt("custom-model-data") : null;
        return new ItemDefinition(material, amount, customId, displayName, List.copyOf(lore), customModelData);
    }

    public ItemStack createVanillaStack() {
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!displayName.isBlank()) meta.setDisplayName(color(displayName));
            if (!lore.isEmpty()) meta.setLore(lore.stream().map(ItemDefinition::color).toList());
            if (customModelData != null) meta.setCustomModelData(customModelData);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}

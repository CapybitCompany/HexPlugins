package hex.minions.crafting;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.List;

public record SpecialItemDefinition(
        String id,
        boolean enabled,
        Material material,
        int customModelData,
        int amount,
        String displayName,
        List<String> lore,
        boolean enchantGlint,
        boolean placeable,
        String blockKind,
        String leatherColor
) {
    public static SpecialItemDefinition fromConfig(String id, ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material", "PAPER"));
        if (material == null) material = Material.PAPER;
        return new SpecialItemDefinition(
                id.toLowerCase(java.util.Locale.ROOT),
                section.getBoolean("enabled", true),
                material,
                Math.max(0, section.getInt("custom-model-data", 0)),
                Math.max(1, section.getInt("amount", 1)),
                section.getString("display-name", id),
                List.copyOf(section.getStringList("lore")),
                section.getBoolean("enchant-glint", false),
                section.getBoolean("placeable", false),
                section.getString("block-kind", ""),
                section.getString("leather-color", "")
        );
    }

    public ItemStack icon(MiniMessage miniMessage) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (customModelData > 0) meta.setCustomModelData(customModelData);
            if (meta instanceof LeatherArmorMeta leatherMeta && leatherColor != null && !leatherColor.isBlank()) {
                Color color = parseColor(leatherColor);
                if (color != null) leatherMeta.setColor(color);
            }
            meta.displayName(miniMessage.deserialize(displayName));
            meta.lore(lore.stream().map(miniMessage::deserialize).toList());
            if (enchantGlint) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (value.length() != 6) return null;
        try {
            int rgb = Integer.parseInt(value, 16);
            return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

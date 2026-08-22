package hex.towns.heart;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class TownHeartItem {
    public static final String KIND = "town_heart";
    public static final int DEFAULT_CUSTOM_MODEL_DATA = 16002;
    private final Plugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey recipeKey;

    public TownHeartItem(Plugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "town_heart_kind");
        this.recipeKey = new NamespacedKey(plugin, "town_heart");
    }

    public NamespacedKey kindKey() {
        return kindKey;
    }

    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.RED_CONCRETE, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int customModelData = plugin.getConfig().getInt(
                    "towns.heart.item.custom-model-data",
                    DEFAULT_CUSTOM_MODEL_DATA
            );
            if (customModelData <= 0) customModelData = DEFAULT_CUSTOM_MODEL_DATA;
            meta.setCustomModelData(customModelData);
            meta.displayName(Component.text("Serce miasta", NamedTextColor.RED));
            meta.lore(List.of(
                    Component.text("Postaw, aby założyć bazę miasta.", NamedTextColor.GRAY),
                    Component.text("Po potwierdzeniu pojawi się w centrum głównego chunka.", NamedTextColor.DARK_GRAY)
            ));
            meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, KIND);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isHeart(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && KIND.equals(meta.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING));
    }

    public void registerRecipe() {
        try {
            plugin.getServer().removeRecipe(recipeKey);
        } catch (Throwable ignored) {
            // Paper/Bukkit versions differ slightly; duplicate registration is harmlessly guarded below.
        }
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, create(1));
        recipe.shape("IGI", "MDM", "CCC");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('G', Material.GOLD_INGOT);
        recipe.setIngredient('M', new RecipeChoice.MaterialChoice(
                Material.BEEF, Material.COOKED_BEEF,
                Material.PORKCHOP, Material.COOKED_PORKCHOP,
                Material.CHICKEN, Material.COOKED_CHICKEN,
                Material.MUTTON, Material.COOKED_MUTTON,
                Material.RABBIT, Material.COOKED_RABBIT
        ));
        recipe.setIngredient('D', Material.DIAMOND);
        recipe.setIngredient('C', Material.COBBLESTONE);
        // Bukkit pobiera po 1 sztuce z kratki. TownHeartListener weryfikuje i dopiera
        // brakujące ilości, dzięki czemu faktyczny koszt może być większy niż 1/slot.
        plugin.getServer().addRecipe(recipe);
    }
}

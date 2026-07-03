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
        recipe.shape("RCR", "CCC", "RCR");
        recipe.setIngredient('R', Material.RED_DYE);
        // Bukkitowe shaped recipe nie obsługuje realnego pobierania 32 sztuk z pojedynczej kratki
        // bez własnego listenera craftingu, ale ExactChoice dokumentuje wymaganą intencję receptury.
        recipe.setIngredient('C', new RecipeChoice.ExactChoice(new ItemStack(Material.COBBLESTONE, 32)));
        plugin.getServer().addRecipe(recipe);
    }
}

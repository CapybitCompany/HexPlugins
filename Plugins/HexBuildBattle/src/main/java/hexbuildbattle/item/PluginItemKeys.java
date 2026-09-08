package hexbuildbattle.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginItemKeys {

    private final NamespacedKey itemType;
    private final NamespacedKey ratingLevel;

    public PluginItemKeys(JavaPlugin plugin) {
        this.itemType = new NamespacedKey(plugin, "item_type");
        this.ratingLevel = new NamespacedKey(plugin, "rating_level");
    }

    public NamespacedKey itemType() {
        return itemType;
    }

    public NamespacedKey ratingLevel() {
        return ratingLevel;
    }
}

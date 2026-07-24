package hexcustomitems.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Optionale PlaceholderAPI-Anbindung. Sämtliche direkten PlaceholderAPI-Referenzen
 * sind hier gekapselt und werden erst aufgelöst, wenn das Plugin wirklich vorhanden
 * ist ({@link #available}). So gibt es keinen harten Fehler, wenn PlaceholderAPI fehlt.
 */
public final class PapiSupport {

    private static volatile boolean available = false;

    private PapiSupport() {
    }

    public static void init(Plugin plugin) {
        available = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (available) {
            plugin.getLogger().info("PlaceholderAPI wykryte - placeholdery w nazwach/lore aktywne.");
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    /** Ersetzt PlaceholderAPI-Platzhalter; ohne PlaceholderAPI bleibt der Text unverändert. */
    public static String apply(OfflinePlayer context, String text) {
        if (!available || text == null || text.isEmpty() || context == null) {
            return text;
        }
        return setPlaceholders(context, text);
    }

    // Getrennte Methode: die PlaceholderAPI-Klasse wird erst hier (lazy) aufgelöst.
    private static String setPlaceholders(OfflinePlayer context, String text) {
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(context, text);
    }
}

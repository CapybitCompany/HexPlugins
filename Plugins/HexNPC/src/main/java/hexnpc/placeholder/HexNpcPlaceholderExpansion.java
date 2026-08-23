package hexnpc.placeholder;

import hexnpc.data.PlayerDataService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI bridge for cached persistent HexNPC player data.
 *
 * <p>Supported placeholders:
 * <ul>
 *   <li>%hexnpc_custom_tag% -> cosmetics.custom_tag</li>
 *   <li>%hexnpc_data_&lt;namespaced.key&gt;% -> arbitrary cached player data</li>
 * </ul>
 *
 * <p>IMPORTANT: Placeholder callbacks are intentionally cache-only. Some consumers
 * (including UnlimitedNameTags 2.x) resolve PlaceholderAPI values from worker
 * threads. Do not call Bukkit/Paper player APIs or database-loading methods here.
 * Player data is preloaded by PlayerLifecycleListener on join and kept current by
 * PlayerDataService#set/delete.
 */
public final class HexNpcPlaceholderExpansion extends PlaceholderExpansion {
    private final PlayerDataService playerData;
    private final String version;

    public HexNpcPlaceholderExpansion(PlayerDataService playerData, String version) {
        this.playerData = playerData;
        this.version = version == null ? "unknown" : version;
    }

    @Override public @NotNull String getIdentifier() { return "hexnpc"; }
    @Override public @NotNull String getAuthor() { return "HexDevTeam"; }
    @Override public @NotNull String getVersion() { return version; }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        final java.util.UUID playerId = player.getUniqueId();
        if (playerId == null) return "";

        // Public integration alias for custom nametags.
        // Legacy compatibility: when the enabled flag is missing, the tag stays enabled.
        if (params.equalsIgnoreCase("custom_tag")) {
            String value = playerData.getCached(playerId, "cosmetics.custom_tag");
            if (value.isBlank()) return "";
            String enabled = playerData.getCached(playerId, "cosmetics.custom_tag.enabled");
            return "false".equalsIgnoreCase(enabled.trim()) ? "" : value;
        }

        if (!params.startsWith("data_")) return null;
        final String key = params.substring("data_".length()).trim();
        if (key.isEmpty()) return "";
        return playerData.getCached(playerId, key);
    }
}

package hexnpc.placeholder;

import hexnpc.data.PlayerDataService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** %hexnpc_data_<namespaced.key>% -> cached persistent HexNPC player data. */
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
        if (player == null || player.getUniqueId() == null) return "";
        if (!params.startsWith("data_")) return null;
        String key = params.substring("data_".length()).trim();
        if (key.isEmpty()) return "";
        if (player.isOnline() && playerData.ready()) {
            // Never block PlaceholderAPI. Warm the cache asynchronously; subsequent
            // refreshes immediately see the persisted value.
            playerData.ensureLoaded(player.getUniqueId());
        }
        return playerData.getCached(player.getUniqueId(), key);
    }
}

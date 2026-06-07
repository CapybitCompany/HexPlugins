package hex.core.placeholder;

import hex.core.api.HexApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HexPlaceholderExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final HexApi api;
    private final HexPlaceholderRegistry registry;

    public HexPlaceholderExpansion(Plugin plugin, HexApi api, HexPlaceholderRegistry registry) {
        this.plugin = plugin;
        this.api = api;
        this.registry = registry;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hex";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        return resolve(player, identifier);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String identifier) {
        return resolve(player instanceof Player online ? online : null, identifier);
    }

    private @Nullable String resolve(@Nullable Player player, @NotNull String identifier) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        HexPlaceholderContext context = new HexPlaceholderContext(api, player, normalizedIdentifier);
        return registry.find(normalizedIdentifier)
                .map(provider -> provider.resolve(context))
                .orElse(null);
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        return identifier.startsWith(":") ? identifier.substring(1) : identifier;
    }
}


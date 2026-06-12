package pl.hex.abovename.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Wraps the original config.yml-backed semantics. Methods complete
 * synchronously on the calling thread; the plugin still treats them as
 * futures so the call sites are uniform across storages.
 */
public final class YamlTitleStorage implements TitleStorage {

    private static final String SECTION = "titles";

    private final JavaPlugin plugin;

    public YamlTitleStorage(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public CompletableFuture<Map<UUID, StoredTitle>> loadAll() {
        Map<UUID, StoredTitle> out = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(SECTION);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String name = plugin.getConfig().getString(SECTION + "." + key + ".name");
                String title = plugin.getConfig().getString(SECTION + "." + key + ".title");
                if (name == null || title == null) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(key);
                    out.put(uuid, new StoredTitle(uuid, name, title));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed UUID keys
                }
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletableFuture<Optional<StoredTitle>> load(UUID uuid) {
        String name = plugin.getConfig().getString(SECTION + "." + uuid + ".name");
        String title = plugin.getConfig().getString(SECTION + "." + uuid + ".title");
        if (name == null || title == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(Optional.of(new StoredTitle(uuid, name, title)));
    }

    @Override
    public CompletableFuture<Void> save(UUID uuid, String playerName, String title) {
        plugin.getConfig().set(SECTION + "." + uuid + ".name", playerName);
        plugin.getConfig().set(SECTION + "." + uuid + ".title", title);
        plugin.saveConfig();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> delete(UUID uuid) {
        plugin.getConfig().set(SECTION + "." + uuid, null);
        plugin.saveConfig();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<UUID>> findUuidByName(String name) {
        if (name == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String needle = name.toLowerCase(Locale.ROOT);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(SECTION);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String stored = plugin.getConfig().getString(SECTION + "." + key + ".name");
                if (stored != null && stored.toLowerCase(Locale.ROOT).equals(needle)) {
                    try {
                        return CompletableFuture.completedFuture(Optional.of(UUID.fromString(key)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }
}

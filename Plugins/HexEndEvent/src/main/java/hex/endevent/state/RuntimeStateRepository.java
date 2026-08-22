package hex.endevent.state;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RuntimeStateRepository {
    public record LoadResult(EndEventRuntimeState state, boolean healthy) { }

    private final Plugin plugin;
    private File file;

    public RuntimeStateRepository(Plugin plugin, String fileName) {
        this.plugin = plugin;
        setFileName(fileName);
    }

    public void setFileName(String fileName) {
        this.file = new File(plugin.getDataFolder(), fileName);
    }

    public LoadResult load() {
        EndEventRuntimeState state = new EndEventRuntimeState();
        if (!file.exists()) return new LoadResult(state, true);
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            int schema = yaml.getInt("schema-version", EndEventRuntimeState.SCHEMA_VERSION);
            if (schema != EndEventRuntimeState.SCHEMA_VERSION) {
                plugin.getLogger().severe("Nieobslugiwana wersja runtime.yml: " + schema);
                return new LoadResult(state, false);
            }
            state.preparedEventId(yaml.getString("prepared-event-id", ""));
            state.activeEventId(yaml.getString("active-event-id", ""));
            state.activeUntil(yaml.getString("active-until", ""));
            state.lastFinishedEventId(yaml.getString("last-finished-event-id", ""));
            state.resetRequired(yaml.getBoolean("reset-required", true));
            state.generationEventId(yaml.getString("generation.event-id", ""));
            state.generationSeed(yaml.getLong("generation.seed", 0L));
            return new LoadResult(state, true);
        } catch (Exception ex) {
            plugin.getLogger().severe("Nie mozna odczytac runtime state HexEndEvent: " + ex.getMessage());
            return new LoadResult(state, false);
        }
    }

    public synchronized void save(EndEventRuntimeState state) {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", EndEventRuntimeState.SCHEMA_VERSION);
            yaml.set("prepared-event-id", state.preparedEventId());
            yaml.set("active-event-id", state.activeEventId());
            yaml.set("active-until", state.activeUntil());
            yaml.set("last-finished-event-id", state.lastFinishedEventId());
            yaml.set("reset-required", state.resetRequired());
            yaml.set("generation.event-id", state.generationEventId());
            yaml.set("generation.seed", state.generationSeed());

            Path target = file.toPath();
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Nie mozna zapisac runtime state HexEndEvent: " + ex.getMessage());
        }
    }
}

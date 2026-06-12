package hex.minions.machine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MachineRegistry {
    private final Map<String, MachineDefinition> machines;

    private MachineRegistry(Map<String, MachineDefinition> machines) {
        this.machines = Map.copyOf(machines);
    }

    public static MachineRegistry load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "machines.yml");
        if (!file.exists()) plugin.saveResource("machines.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, MachineDefinition> machines = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("machines");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                MachineDefinition definition = MachineDefinition.fromConfig(id, section);
                if (definition.enabled()) machines.put(definition.id(), definition);
            }
        }
        return new MachineRegistry(machines);
    }

    public Map<String, MachineDefinition> machines() { return machines; }
    public Optional<MachineDefinition> byStation(String stationId) {
        if (stationId == null) return Optional.empty();
        return machines.values().stream().filter(m -> m.stationId().equalsIgnoreCase(stationId) || m.id().equalsIgnoreCase(stationId)).findFirst();
    }
}

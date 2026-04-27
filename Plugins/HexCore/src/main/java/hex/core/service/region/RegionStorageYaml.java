package hex.core.service.region;

import hex.core.api.region.BlockPos;
import hex.core.api.region.Region;
import hex.core.api.region.RegionKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class RegionStorageYaml {

    private final File file;

    public RegionStorageYaml(File file) {
        this.file = file;
    }

    public Map<RegionKey, Region> load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection root = yml.getConfigurationSection("regions");
        if (root == null) return new HashMap<>();

        Map<RegionKey, Region> out = new HashMap<>();

        for (String keyStr : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(keyStr);
            if (sec == null) continue;

            RegionKey key = RegionKey.parse(keyStr);

            String world = sec.getString("world", "");
            String minStr = sec.getString("min", "");
            String maxStr = sec.getString("max", "");
            if (world.isBlank() || minStr.isBlank() || maxStr.isBlank()) continue;

            BlockPos min = BlockPos.parseCsv(minStr);
            BlockPos max = BlockPos.parseCsv(maxStr);

            Map<String, String> meta = new HashMap<>();
            ConfigurationSection metaSec = sec.getConfigurationSection("meta");
            if (metaSec != null) {
                for (String mk : metaSec.getKeys(false)) {
                    String mv = metaSec.getString(mk);
                    if (mv != null) meta.put(mk, mv);
                }
            }

            out.put(key, new Region(key, world, min, max, meta));
        }

        return out;
    }

    public void save(Map<RegionKey, Region> regions) {
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection root = yml.createSection("regions");

        List<RegionKey> keys = new ArrayList<>(regions.keySet());
        keys.sort(Comparator.comparing(RegionKey::toString));

        for (RegionKey k : keys) {
            Region r = regions.get(k);
            ConfigurationSection sec = root.createSection(k.toString());
            sec.set("world", r.world());
            sec.set("min", r.min().toCsv());
            sec.set("max", r.max().toCsv());

            if (!r.meta().isEmpty()) {
                ConfigurationSection metaSec = sec.createSection("meta");
                for (var e : r.meta().entrySet()) {
                    metaSec.set(e.getKey(), e.getValue());
                }
            }
        }

        try {
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save regions.yml", e);
        }
    }
}

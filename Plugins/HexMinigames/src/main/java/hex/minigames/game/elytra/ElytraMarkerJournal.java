package hex.minigames.game.elytra;
import hex.minigames.model.BlockPosition;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable write-ahead snapshot: markers and gates can be restored after stop, reload or a crash. */
public final class ElytraMarkerJournal {
    private ElytraMarkerJournal() { }
    private static File file(Plugin plugin) { return new File(plugin.getDataFolder(), "elytra-marker-recovery.yml"); }
    public static void save(Plugin plugin, World world, Set<BlockPosition> positions) {
        File file = file(plugin);
        if (file.exists()) throw new IllegalStateException("Unrecovered Elytra markers: " + file);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", world.getName());
        List<Map<String,Object>> blocks = new ArrayList<>();
        for (var p : positions) blocks.add(Map.of("x",p.x(),"y",p.y(),"z",p.z(),
                "data",world.getBlockAt(p.x(),p.y(),p.z()).getBlockData().getAsString()));
        yaml.set("blocks", blocks);
        try {
            Files.createDirectories(file.toPath().getParent());
            Path temporary = file.toPath().resolveSibling(file.getName() + ".tmp");
            yaml.save(temporary.toFile());
            Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException("Could not save Elytra markers before removal", error); }
    }
    public static void recover(Plugin plugin) {
        File file = file(plugin);
        if (!file.exists()) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file);
            World world = Bukkit.getWorld(Objects.requireNonNull(yaml.getString("world")));
            if (world == null) throw new IllegalStateException("Elytra recovery world is unavailable; snapshot retained");
            // Parse all data before touching the world or deleting the recovery file.
            Map<BlockPosition, org.bukkit.block.data.BlockData> blocks = new LinkedHashMap<>();
            for (Map<?,?> row : yaml.getMapList("blocks")) blocks.put(new BlockPosition(((Number)row.get("x")).intValue(),
                    ((Number)row.get("y")).intValue(), ((Number)row.get("z")).intValue()), Bukkit.createBlockData((String)row.get("data")));
            if (blocks.isEmpty()) throw new IllegalStateException("Empty Elytra recovery journal; retained for inspection");
            blocks.forEach((p,data) -> world.getBlockAt(p.x(),p.y(),p.z()).setBlockData(data, false));
            Files.delete(file.toPath());
        } catch (Exception error) { throw new IllegalStateException("Could not restore Elytra markers; journal retained", error); }
    }
}

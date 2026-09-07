package hex.parkour.config;

import org.bukkit.Material;

import java.util.List;

public record ItemConfig(Material material, int slot, String name, List<String> lore) {
}

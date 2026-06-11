package hex.towns.map;

import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TownMapService {
    private final Plugin plugin;
    private final TownsService service;
    private final TownsConfig config;

    public TownMapService(Plugin plugin, TownsService service, TownsConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
    }

    public void openMap(Player player) {
        World world = player.getWorld();
        MapView view = Bukkit.createMap(world);
        for (MapRenderer renderer : view.getRenderers()) {
            view.removeRenderer(renderer);
        }
        int centerX = player.getChunk().getX();
        int centerZ = player.getChunk().getZ();
        int radius = config.mapRadiusChunks();
        UUID ownTownId = service.townIdOf(player.getUniqueId()).orElse(null);
        Map<UUID, Town> nearby = new LinkedHashMap<>();
        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                service.townAt(world.getName(), x, z).ifPresent(town -> nearby.putIfAbsent(town.id(), town));
            }
        }
        view.addRenderer(new TownMapRenderer(service, world.getName(), centerX, centerZ, radius, ownTownId, nearby));
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName("§eMapa miast");
            meta.setLore(java.util.List.of("§7Granice miasta i sąsiednie miasta", "§8Centrum: " + centerX + ", " + centerZ));
            map.setItemMeta(meta);
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(map);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), map);
        }
        player.sendMap(view);
        player.sendMessage("§aDodano mapę miast do ekwipunku. Weź ją do ręki, aby zobaczyć granice i nazwy miast.");
        plugin.getLogger().fine("Generated HexTowns map for " + player.getName() + " near " + centerX + "," + centerZ);
    }
}

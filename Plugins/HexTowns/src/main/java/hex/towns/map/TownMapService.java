package hex.towns.map;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TownMapService {
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsService service;
    private volatile TownsConfig config;
    private final NamespacedKey townMapKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public TownMapService(Plugin plugin, HexApi hex, TownsService service, TownsConfig config) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.config = config;
        this.townMapKey = new NamespacedKey(plugin, "town_map");
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
    }

    public void openMap(Player player) {
        long now = System.currentTimeMillis();
        long availableAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (availableAt > now) {
            long seconds = Math.max(1L, (availableAt - now + 999L) / 1000L);
            hex.ui().send(player, "towns.map.cooldown", UiTokens.of("seconds", String.valueOf(seconds)));
            return;
        }
        cooldowns.put(player.getUniqueId(), now + config.mapCooldownSeconds() * 1000L);

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
        ItemStack map = createMapItem(view, centerX, centerZ);

        int existingSlot = findExistingTownMapSlot(player);
        if (config.mapPreventDuplicates() && existingSlot >= 0) {
            player.getInventory().setItem(existingSlot, map);
            player.sendMap(view);
            hex.ui().send(player, "towns.map.refreshed");
            plugin.getLogger().fine("Refreshed HexTowns map for " + player.getName() + " near " + centerX + "," + centerZ);
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(map);
        if (!leftover.isEmpty()) {
            hex.ui().send(player, "towns.map.no-space");
            return;
        }
        player.sendMap(view);
        hex.ui().send(player, "towns.map.created");
        plugin.getLogger().fine("Generated HexTowns map for " + player.getName() + " near " + centerX + "," + centerZ);
    }

    private ItemStack createMapItem(MapView view, int centerX, int centerZ) {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName("§eMapa miast");
            meta.setLore(java.util.List.of(
                    "§7Granice miasta i sąsiednie miasta",
                    "§8Centrum: " + centerX + ", " + centerZ,
                    "§8Mapa HexTowns - odświeżana przez /town map"
            ));
            meta.getPersistentDataContainer().set(townMapKey, PersistentDataType.BYTE, (byte) 1);
            map.setItemMeta(meta);
        }
        return map;
    }

    private int findExistingTownMapSlot(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isTownMap(item)) return i;
        }
        return -1;
    }

    private boolean isTownMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(townMapKey, PersistentDataType.BYTE);
    }
}

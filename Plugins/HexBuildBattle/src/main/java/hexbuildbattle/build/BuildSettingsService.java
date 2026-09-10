package hexbuildbattle.build;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.arena.ArenaManager;
import hexbuildbattle.arena.ArenaVisualSettings;
import hexbuildbattle.config.ConfigParsers;
import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.item.ItemBuilder;
import hexbuildbattle.item.PluginItemKeys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.WeatherType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuildSettingsService {

    public static final String COMPASS_TYPE = "settings_compass";
    private static final Set<String> HARD_FLOOR_BLACKLIST = Set.of(
            "AIR",
            "CAVE_AIR",
            "VOID_AIR",
            "TNT",
            "BEDROCK",
            "COMMAND_BLOCK",
            "CHAIN_COMMAND_BLOCK",
            "REPEATING_COMMAND_BLOCK",
            "STRUCTURE_BLOCK",
            "JIGSAW",
            "END_PORTAL",
            "NETHER_PORTAL",
            "END_GATEWAY"
    );

    private final ConfigService configService;
    private final MessageService messages;
    private final ArenaManager arenaManager;
    private final PluginItemKeys itemKeys;

    public BuildSettingsService(
            ConfigService configService,
            MessageService messages,
            ArenaManager arenaManager,
            PluginItemKeys itemKeys
    ) {
        this.configService = configService;
        this.messages = messages;
        this.arenaManager = arenaManager;
        this.itemKeys = itemKeys;
    }

    public void giveCompass(Player player) {
        player.getInventory().setItem(0, compassItem());
    }

    public void enforceCompass(Player player) {
        ItemStack slotZero = player.getInventory().getItem(0);
        if (!isCompass(slotZero)) {
            player.getInventory().setItem(0, compassItem());
        }
    }

    public boolean isCompass(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String type = item.getItemMeta().getPersistentDataContainer()
                .get(itemKeys.itemType(), PersistentDataType.STRING);
        return COMPASS_TYPE.equals(type);
    }

    public void openRoot(Player player) {
        BuildSettingsHolder holder = new BuildSettingsHolder(BuildSettingsMenuType.ROOT);
        Inventory inventory = Bukkit.createInventory(
                holder,
                inventorySize("settings.size", 27),
                ItemBuilder.component(configService.gui().getString("settings.title", "&0&lUstawienia działki"))
        );
        holder.attach(inventory);
        inventory.setItem(configService.gui().getInt("settings.floor.slot", 11),
                guiItem("settings.floor.material", Material.BLACK_WOOL, "settings.floor.display-name", "&aPodłoga"));
        inventory.setItem(configService.gui().getInt("settings.weather.slot", 13),
                guiItem("settings.weather.material", Material.WATER_BUCKET, "settings.weather.display-name", "&bPogoda"));
        inventory.setItem(configService.gui().getInt("settings.time.slot", 15),
                guiItem("settings.time.material", Material.CLOCK, "settings.time.display-name", "&eCzas"));
        player.openInventory(inventory);
    }

    public boolean handleClick(Player player, InventoryClickEvent event, Arena arena) {
        if (!(event.getInventory().getHolder() instanceof BuildSettingsHolder holder)) {
            return false;
        }
        switch (holder.type()) {
            case FLOOR -> handleFloorClick(player, event, arena);
            case ROOT, WEATHER, TIME -> {
                event.setCancelled(true);
                if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
                    return true;
                }
                switch (holder.type()) {
                    case ROOT -> handleRootClick(player, event.getRawSlot());
                    case WEATHER -> handleWeatherClick(player, event.getRawSlot(), arena);
                    case TIME -> handleTimeClick(player, event.getRawSlot(), arena);
                    default -> {
                    }
                }
            }
        }
        return true;
    }

    public boolean handleDrag(Player player, InventoryDragEvent event, Arena arena) {
        if (!(event.getInventory().getHolder() instanceof BuildSettingsHolder holder)) {
            return false;
        }
        if (holder.type() != BuildSettingsMenuType.FLOOR) {
            event.setCancelled(true);
            return true;
        }

        int inventorySize = event.getInventory().getSize();
        boolean touchesTopInventory = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < inventorySize);
        if (event.getRawSlots().contains(configService.gui().getInt("floor.input.slot", 13))) {
            event.setCancelled(true);
            applyFloorCandidate(player, event.getOldCursor(), arena);
        } else if (touchesTopInventory) {
            event.setCancelled(true);
        }
        return true;
    }

    public void applyVisuals(Player player, Arena arena) {
        ArenaVisualSettings settings = arenaManager.visualSettings(arena);
        player.setPlayerTime(settings.time().ticks(), false);
        WeatherType weather = settings.weather().bukkitWeather();
        player.setPlayerWeather(weather);
    }

    public boolean isSnowing(Arena arena) {
        return arenaManager.visualSettings(arena).weather() == ArenaVisualSettings.WeatherPreset.SNOW;
    }

    public void resetVisuals(Player player) {
        player.resetPlayerTime();
        player.resetPlayerWeather();
    }

    public void resetFloors(Collection<Arena> arenas) {
        for (Arena arena : arenas) {
            setFloor(arena, configService.config().materials().defaultFloor());
        }
    }

    public Material floorMaterial(Arena arena) {
        return arenaManager.visualSettings(arena).floorMaterial();
    }

    private void handleRootClick(Player player, int slot) {
        if (slot == configService.gui().getInt("settings.floor.slot", 11)) {
            openFloor(player);
        } else if (slot == configService.gui().getInt("settings.weather.slot", 13)) {
            openWeather(player);
        } else if (slot == configService.gui().getInt("settings.time.slot", 15)) {
            openTime(player);
        }
    }

    private void handleFloorClick(Player player, InventoryClickEvent event, Arena arena) {
        int inventorySize = event.getInventory().getSize();
        if (event.getRawSlot() >= inventorySize) {
            if (event.isShiftClick() || event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == configService.gui().getInt("floor.input.slot", 13)) {
            applyFloorCandidate(player, event.getCursor(), arena);
        }
    }

    private void applyFloorCandidate(Player player, ItemStack item, Arena arena) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        Material material = floorMaterialFromItem(item.getType());
        if (!isAllowedFloorMaterial(material)) {
            messages.sendWithPrefix(player, "building.floor-invalid", Map.of("material", item.getType().name()));
            configService.config().sounds().floorInvalid().play(player);
            return;
        }
        setFloor(arena, material);
        messages.sendWithPrefix(player, "building.floor-changed", Map.of("material", material.name()));
        configService.config().sounds().floorChanged().play(player);
        player.closeInventory();
    }

    private void setFloor(Arena arena, Material material) {
        arenaManager.visualSettings(arena).floorMaterial(material);
        for (Block block : floorBlocks(arena)) {
            block.setType(material, false);
        }
    }

    private void handleWeatherClick(Player player, int slot, Arena arena) {
        ArenaVisualSettings.WeatherPreset selected = switch (slot) {
            case 11 -> ArenaVisualSettings.WeatherPreset.CLEAR;
            case 15 -> ArenaVisualSettings.WeatherPreset.RAIN;
            default -> null;
        };
        if (selected == null) {
            return;
        }
        arenaManager.visualSettings(arena).weather(selected);
        applyVisuals(player, arena);
        messages.sendWithPrefix(player, "building.weather-changed", Map.of("weather", weatherName(selected)));
        player.closeInventory();
    }

    private void handleTimeClick(Player player, int slot, Arena arena) {
        ArenaVisualSettings.TimePreset selected = switch (slot) {
            case 11 -> ArenaVisualSettings.TimePreset.DAY;
            case 15 -> ArenaVisualSettings.TimePreset.NIGHT;
            default -> null;
        };
        if (selected == null) {
            return;
        }
        arenaManager.visualSettings(arena).time(selected);
        applyVisuals(player, arena);
        messages.sendWithPrefix(player, "building.time-changed", Map.of("time", timeName(selected)));
        player.closeInventory();
    }

    private void openFloor(Player player) {
        BuildSettingsHolder holder = new BuildSettingsHolder(BuildSettingsMenuType.FLOOR);
        Inventory inventory = Bukkit.createInventory(
                holder,
                inventorySize("floor.size", 54),
                ItemBuilder.component(configService.gui().getString("floor.title", "&0&lPodłoga"))
        );
        holder.attach(inventory);
        int inputSlot = configService.gui().getInt("floor.input.slot", 13);
        if (inputSlot >= 0 && inputSlot < inventory.getSize()) {
            inventory.setItem(inputSlot, guiItem("floor.input.material", Material.BLACK_WOOL,
                    "floor.input.display-name", "&aWskaż blok podłogi"));
        }
        player.openInventory(inventory);
    }

    private void openWeather(Player player) {
        BuildSettingsHolder holder = new BuildSettingsHolder(BuildSettingsMenuType.WEATHER);
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                ItemBuilder.component(configService.gui().getString("weather.title", "&0&lPogoda"))
        );
        holder.attach(inventory);
        inventory.setItem(11, guiItem("weather.clear.material", Material.SUNFLOWER,
                "weather.clear.display-name", "&eSłonecznie"));
        inventory.setItem(15, guiItem("weather.rain.material", Material.WATER_BUCKET,
                "weather.rain.display-name", "&9Deszcz"));
        player.openInventory(inventory);
    }

    private void openTime(Player player) {
        BuildSettingsHolder holder = new BuildSettingsHolder(BuildSettingsMenuType.TIME);
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                ItemBuilder.component(configService.gui().getString("time.title", "&0&lCzas"))
        );
        holder.attach(inventory);
        inventory.setItem(11, guiItem("time.day.material", Material.CLOCK,
                "time.day.display-name", "&eDzień"));
        inventory.setItem(15, guiItem("time.night.material", Material.BLACK_DYE,
                "time.night.display-name", "&9Noc"));
        player.openInventory(inventory);
    }

    private ItemStack guiItem(String materialPath, Material fallback, String namePath, String fallbackName) {
        Material material = ConfigParsers.material(
                configService.gui().getString(materialPath),
                fallback,
                java.util.logging.Logger.getLogger("HexBuildBattle"),
                "gui.yml:" + materialPath
        );
        return ItemBuilder.named(material, configService.gui().getString(namePath, fallbackName));
    }

    private ItemStack compassItem() {
        ItemStack item = ItemBuilder.named(
                Material.COMPASS,
                messages.raw("building.settings-compass-name", "&6Ustawienia działki"),
                messages.rawList("building.settings-compass-lore")
        );
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKeys.itemType(), PersistentDataType.STRING, COMPASS_TYPE);
        item.setItemMeta(meta);
        return item;
    }

    private int inventorySize(String path, int fallback) {
        int size = configService.gui().getInt(path, fallback);
        if (size < 9 || size > 54 || size % 9 != 0) {
            return fallback;
        }
        return size;
    }

    private List<Block> floorBlocks(Arena arena) {
        List<Block> blocks = new ArrayList<>();
        for (int x = arena.floorRegion().minX(); x <= arena.floorRegion().maxX(); x++) {
            for (int z = arena.floorRegion().minZ(); z <= arena.floorRegion().maxZ(); z++) {
                blocks.add(arena.floorRegion().world().getBlockAt(x, arena.floorRegion().minY(), z));
            }
        }
        return blocks;
    }

    private boolean isAllowedFloorMaterial(Material material) {
        if (material == null || material.isAir() || !material.isBlock()) {
            return false;
        }
        String name = material.name();
        return !HARD_FLOOR_BLACKLIST.contains(name)
                && !name.endsWith("_COMMAND_BLOCK")
                && !name.contains("PORTAL");
    }

    private Material floorMaterialFromItem(Material itemMaterial) {
        return switch (itemMaterial) {
            case WATER_BUCKET -> Material.WATER;
            case LAVA_BUCKET -> Material.LAVA;
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
            default -> itemMaterial;
        };
    }

    private String weatherName(ArenaVisualSettings.WeatherPreset weather) {
        return messages.raw("building.weather-name." + weather.name(), weather.name());
    }

    private String timeName(ArenaVisualSettings.TimePreset time) {
        return messages.raw("building.time-name." + time.name(), time.name());
    }
}

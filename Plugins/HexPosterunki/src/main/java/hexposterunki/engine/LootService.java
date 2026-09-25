package hexposterunki.engine;

import hexposterunki.config.BlockVec;
import hexposterunki.config.LootConfig;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.util.RandomSource;
import hexposterunki.util.Weighted;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Fills and resets the loot containers of an outpost. Main thread only.
 *
 * <p>Each container is filled exactly once per run; the "already filled" decision belongs to the
 * database ({@code posterunki_loot} primary key), so a restart during the fill phase cannot produce
 * a second payout.
 */
public final class LootService {

    private final RandomSource random;
    private final Logger logger;
    private final Set<String> unlocked = new LinkedHashSet<>();

    public LootService(RandomSource random, Logger logger) {
        this.random = Objects.requireNonNull(random, "random");
        this.logger = logger;
    }

    public static String containerId(String outpostId, String containerName) {
        return outpostId + ":" + containerName;
    }

    /** Marks containers as openable; recovery replays this from the database. */
    public void markUnlocked(String containerId) {
        unlocked.add(containerId);
    }

    public boolean isUnlocked(String outpostId, String containerName) {
        return unlocked.contains(containerId(outpostId, containerName));
    }

    public void clearUnlocked() {
        unlocked.clear();
    }

    /**
     * Rolls and writes the contents of one container.
     *
     * @return true when the container existed and was filled
     */
    public boolean fill(OutpostDefinition outpost, String containerName, LootConfig config) {
        Optional<Inventory> inventory = inventoryOf(outpost, containerName);
        if (inventory.isEmpty()) {
            return false;
        }
        String tableId = config.tableFor(outpost.id(), containerName);
        LootConfig.LootTable table = config.tables().get(tableId);
        if (table == null) {
            logger.warning("[loot] Brak tabeli lootu '" + tableId + "' dla kontenera '"
                    + containerName + "' na posterunku '" + outpost.id() + "'.");
            return false;
        }
        Inventory target = inventory.get();
        target.clear();
        for (ItemStack stack : roll(table)) {
            target.addItem(stack);
        }
        markUnlocked(containerId(outpost.id(), containerName));
        return true;
    }

    /** Resets every configured container of the outpost to the configured reset state. */
    public int reset(OutpostDefinition outpost, LootConfig config) {
        int touched = 0;
        for (String containerName : outpost.lootContainers().keySet()) {
            Optional<Inventory> inventory = inventoryOf(outpost, containerName);
            if (inventory.isEmpty()) {
                continue;
            }
            Inventory target = inventory.get();
            target.clear();
            if (config.resetMode() == LootConfig.ResetMode.RESTORE_INITIAL && config.initialTable() != null) {
                LootConfig.LootTable initial = config.tables().get(config.initialTable());
                if (initial == null) {
                    logger.warning("[loot] initial-table '" + config.initialTable()
                            + "' nie istnieje - kontener '" + containerName + "' pozostaje pusty.");
                } else {
                    for (ItemStack stack : roll(initial)) {
                        target.addItem(stack);
                    }
                }
            }
            touched++;
        }
        clearUnlocked();
        return touched;
    }

    private List<ItemStack> roll(LootConfig.LootTable table) {
        List<ItemStack> result = new ArrayList<>();
        if (table.entries().isEmpty()) {
            return result;
        }
        for (int i = 0; i < table.rolls(); i++) {
            Optional<LootConfig.LootEntry> picked =
                    Weighted.pick(table.entries(), LootConfig.LootEntry::weight, random);
            if (picked.isEmpty()) {
                continue;
            }
            LootConfig.LootEntry entry = picked.get();
            Material material = Material.matchMaterial(entry.material());
            if (material == null || material.isAir()) {
                logger.warning("[loot] Nieznany materiał '" + entry.material() + "' - pomijam wpis.");
                continue;
            }
            int span = entry.maxAmount() - entry.minAmount() + 1;
            int amount = entry.minAmount() + (span <= 1 ? 0 : random.nextInt(span));
            result.add(new ItemStack(material, Math.max(1, amount)));
        }
        return result;
    }

    private Optional<Inventory> inventoryOf(OutpostDefinition outpost, String containerName) {
        BlockVec position = outpost.lootContainers().get(containerName);
        if (position == null) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            return Optional.empty();
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            logger.warning("[loot] Blok " + position.toCsv() + " na posterunku '" + outpost.id()
                    + "' nie jest kontenerem (" + block.getType() + ").");
            return Optional.empty();
        }
        return Optional.of(container.getInventory());
    }

    /**
     * Name of the configured loot container at this position.
     *
     * <p>Both halves of a double chest resolve to the same configured container, so an admin only
     * has to list one of the two block positions.
     */
    public static Optional<String> containerNameAt(OutpostDefinition outpost, Location location) {
        if (location == null) {
            return Optional.empty();
        }
        Optional<String> direct = containerNameAt(outpost, location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
        if (direct.isPresent()) {
            return direct;
        }
        World world = location.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        BlockState state = world.getBlockAt(location).getState();
        if (!(state instanceof Chest chest)) {
            return Optional.empty();
        }
        InventoryHolder holder = chest.getInventory().getHolder();
        if (!(holder instanceof DoubleChest doubleChest)) {
            return Optional.empty();
        }
        for (InventoryHolder side : List.of(doubleChest.getLeftSide(), doubleChest.getRightSide())) {
            if (!(side instanceof Chest sideChest)) {
                continue;
            }
            Location sideLocation = sideChest.getLocation();
            Optional<String> found = containerNameAt(outpost, sideLocation.getBlockX(),
                    sideLocation.getBlockY(), sideLocation.getBlockZ());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** Pure coordinate lookup; the public overload adds double-chest resolution on top. */
    public static Optional<String> containerNameAt(OutpostDefinition outpost, int x, int y, int z) {
        for (Map.Entry<String, BlockVec> entry : outpost.lootContainers().entrySet()) {
            BlockVec position = entry.getValue();
            if (position.x() == x && position.y() == y && position.z() == z) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}

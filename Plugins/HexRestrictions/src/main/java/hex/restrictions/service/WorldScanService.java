package hex.restrictions.service;

import hex.restrictions.HexRestrictionsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class WorldScanService {
    private final HexRestrictionsPlugin plugin;
    private final RestrictionService restrictions;
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    private BukkitTask worker;

    public WorldScanService(HexRestrictionsPlugin plugin, RestrictionService restrictions) {
        this.plugin = plugin;
        this.restrictions = restrictions;
    }

    public void start() {
        if (worker != null) worker.cancel();
        worker = Bukkit.getScheduler().runTaskTimer(plugin, this::work, 1L, 1L);
    }

    public void stop() {
        if (worker != null) {
            worker.cancel();
            worker = null;
        }
        chunks.clear();
    }

    public void queueLoadedChunks() {
        chunks.clear();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) queue(chunk);
        }
    }

    public void queue(Chunk chunk) {
        if (chunk == null) return;
        chunks.addLast(chunk);
    }

    public int queuedChunks() {
        return chunks.size();
    }

    public RestrictionAudit scanChunk(Chunk chunk) {
        if (chunk == null || !chunk.isLoaded() || !restrictions.isEnabled()) return RestrictionAudit.NONE;

        RestrictionAudit audit = RestrictionAudit.NONE;
        if (restrictions.settings().scanContainersOnChunkLoad()) {
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof InventoryHolder holder) {
                    audit = audit.plus(restrictions.sanitizeInventory(holder.getInventory()));
                }
            }
        }

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item itemEntity) {
                audit = audit.plus(sanitizeWorldItem(itemEntity));
            } else if (entity instanceof ItemFrame frame) {
                audit = audit.plus(sanitizeFrame(frame));
            } else if (entity instanceof ItemDisplay display) {
                audit = audit.plus(sanitizeDisplay(display));
            }

            if (entity instanceof AbstractVillager villager && restrictions.settings().cleanVillagerTrades()) {
                audit = audit.plus(cleanVillagerTrades(villager));
            }
        }

        return audit;
    }

    private void work() {
        if (!restrictions.isEnabled() || chunks.isEmpty()) return;
        int limit = restrictions.settings().chunksPerTick();
        RestrictionAudit total = RestrictionAudit.NONE;
        int scanned = 0;
        while (scanned < limit && !chunks.isEmpty()) {
            Chunk chunk = chunks.pollFirst();
            if (chunk == null) break;
            total = total.plus(scanChunk(chunk));
            scanned++;
        }
        if (total.totalChanges() > 0 && restrictions.settings().logScanSummaries()) {
            plugin.getLogger().info("Chunk restriction scan removed items=" + total.removedItems()
                    + ", enchantments=" + total.removedEnchantments()
                    + ", trades=" + total.removedTrades()
                    + ", remainingChunks=" + chunks.size());
        }
    }

    private RestrictionAudit sanitizeWorldItem(Item entity) {
        ItemSanitizeResult result = restrictions.sanitizeItem(entity.getItemStack());
        if (!result.changed()) return RestrictionAudit.NONE;
        if (result.item() == null) entity.remove();
        else entity.setItemStack(result.item());
        return result.audit();
    }

    private RestrictionAudit sanitizeFrame(ItemFrame frame) {
        ItemSanitizeResult result = restrictions.sanitizeItem(frame.getItem());
        if (!result.changed()) return RestrictionAudit.NONE;
        frame.setItem(result.item() == null ? new ItemStack(org.bukkit.Material.AIR) : result.item(), false);
        return result.audit();
    }

    private RestrictionAudit sanitizeDisplay(ItemDisplay display) {
        ItemSanitizeResult result = restrictions.sanitizeItem(display.getItemStack());
        if (!result.changed()) return RestrictionAudit.NONE;
        display.setItemStack(result.item() == null ? new ItemStack(org.bukkit.Material.AIR) : result.item());
        return result.audit();
    }

    private RestrictionAudit cleanVillagerTrades(AbstractVillager villager) {
        List<MerchantRecipe> original = villager.getRecipes();
        List<MerchantRecipe> allowed = new ArrayList<>(original.size());
        int removed = 0;
        for (MerchantRecipe recipe : original) {
            if (restrictions.hasForbiddenContent(recipe.getResult())) removed++;
            else allowed.add(recipe);
        }
        if (removed > 0) villager.setRecipes(allowed);
        return new RestrictionAudit(0, 0, removed);
    }
}

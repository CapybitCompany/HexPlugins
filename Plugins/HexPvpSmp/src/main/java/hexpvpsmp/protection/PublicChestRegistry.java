package hexpvpsmp.protection;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.region.PublicChest;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit allowlist of container blocks players may use inside spawn.
 * Reads the config supplier live so reloads are reflected. Kept separate from
 * the region-based {@link ProtectionService} on purpose: public chests are a
 * targeted per-block allowlist, not an area — this keeps spawn protection from
 * becoming leaky through a blanket interact bypass.
 *
 * <p>Double chests are supported: configuring a single half is enough. When a
 * player clicks either half, {@link #isPublicChest(Block)} resolves the paired
 * half via {@link DoubleChests} so opening/protecting the whole chest works.
 */
public final class PublicChestRegistry {

    private final Supplier<HexPvpConfig> configSupplier;

    public PublicChestRegistry(Supplier<HexPvpConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public boolean isPublicChest(String worldName, int x, int y, int z) {
        HexPvpConfig config = configSupplier.get();
        if (config == null || worldName == null) {
            return false;
        }
        WorldConfig world = config.world(worldName.toLowerCase(Locale.ROOT)).orElse(null);
        if (world == null || !world.enabled()) {
            // A disabled world has no active protection; its chests aren't special.
            return false;
        }
        for (PublicChest chest : world.publicChests()) {
            if (chest.matches(worldName, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Block-aware check. Matches the block's own coordinate and, if the block is
     * one half of a double chest, its partner half — so configuring a single
     * half whitelists the whole chest.
     *
     * <p>Only {@link Material#CHEST} and {@link Material#TRAPPED_CHEST} blocks may
     * ever be treated as a public chest, so a mis-set coordinate that happens to
     * point at some other block or container is never accidentally whitelisted.
     */
    public boolean isPublicChest(Block block) {
        if (block == null || block.getWorld() == null || !isChestBlock(block.getType())) {
            return false;
        }
        String worldName = block.getWorld().getName();
        if (isPublicChest(worldName, block.getX(), block.getY(), block.getZ())) {
            return true;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Chest chest) {
            BlockFace dir = DoubleChests.partnerDirection(chest.getFacing(), chest.getType());
            if (dir != null) {
                Block partner = block.getRelative(dir);
                return isPublicChest(worldName, partner.getX(), partner.getY(), partner.getZ());
            }
        }
        return false;
    }

    private static boolean isChestBlock(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }
}

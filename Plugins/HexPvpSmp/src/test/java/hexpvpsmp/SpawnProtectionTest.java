package hexpvpsmp;

import hexpvpsmp.combat.PermissionGate;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnProtectionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Builder");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Block blockAt(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    // Inside spawn safezone.
    private Block spawnBlock() {
        return blockAt(10, 64, 10);
    }

    // Inside front_spawn no-build zone (z in [101,180]).
    private Block noBuildBlock() {
        return blockAt(10, 64, 150);
    }

    // Wilderness.
    private Block wildBlock() {
        return blockAt(500, 64, 500);
    }

    private boolean fireBreak(Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePlace(Block block) {
        Block against = block.getRelative(BlockFace.DOWN);
        BlockPlaceEvent event = new BlockPlaceEvent(
                block, block.getState(), against,
                new ItemStack(Material.STONE), player, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean fireInteract(Block block) {
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.AIR), block, BlockFace.UP);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void buildingInSpawnIsBlocked() {
        assertTrue(fireBreak(spawnBlock()), "break in spawn must be blocked");
        assertTrue(firePlace(spawnBlock()), "place in spawn must be blocked");
    }

    @Test
    void buildingInNoBuildZoneIsBlocked() {
        assertTrue(fireBreak(noBuildBlock()), "break in no-build zone must be blocked");
        assertTrue(firePlace(noBuildBlock()), "place in no-build zone must be blocked");
    }

    @Test
    void buildingInWildernessIsAllowed() {
        assertFalse(fireBreak(wildBlock()), "break in wilderness must be allowed");
        assertFalse(firePlace(wildBlock()), "place in wilderness must be allowed");
    }

    @Test
    void bypassPlayerCanBuildInSpawn() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        assertFalse(fireBreak(spawnBlock()), "bypass player may break in spawn");
        assertFalse(firePlace(spawnBlock()), "bypass player may place in spawn");
    }

    @Test
    void buildingIsBlockedAtDifferentHeightsInSpawn() {
        int high = server.getWorld("world").getMaxHeight() - 1;
        int low = server.getWorld("world").getMinHeight();
        assertTrue(fireBreak(blockAt(10, high, 10)), "break high in spawn must be blocked");
        assertTrue(fireBreak(blockAt(10, low, 10)), "break deep in spawn must be blocked");
        assertTrue(fireBreak(blockAt(10, high, 150)), "break high in no-build zone must be blocked");
    }

    @Test
    void publicChestMatchingIsExactIncludingY() {
        // Same X/Z but different Y than the configured chest (0,65,0) is NOT a
        // public chest — it's just a protected block inside spawn.
        Block wrongY = blockAt(0, 70, 0);
        wrongY.setType(Material.CHEST);
        assertTrue(fireInteract(wrongY),
                "a chest at the same X/Z but different Y is not the public chest");
        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));
        assertFalse(plugin.publicChestRegistry().isPublicChest("world", 0, 70, 0));
    }

    @Test
    void publicChestCanBeOpenedInSpawn() {
        Block chest = blockAt(0, 65, 0); // configured public chest
        chest.setType(Material.CHEST);
        assertFalse(fireInteract(chest),
                "public chest must be openable even inside spawn");
    }

    @Test
    void publicChestCannotBeBroken() {
        Block chest = blockAt(0, 65, 0);
        chest.setType(Material.CHEST);
        assertTrue(fireBreak(chest), "public chest must be protected from breaking");
    }

    @Test
    void explosionsDoNotDestroyBlocksInSpawnOrPublicChest() {
        Block chest = blockAt(0, 65, 0);
        List<Block> affected = new ArrayList<>(List.of(spawnBlock(), noBuildBlock(), chest, wildBlock()));

        EntityExplodeEvent event = new EntityExplodeEvent(
                player, spawnBlock().getLocation(), affected, 1.0f, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);

        assertFalse(affected.contains(spawnBlock()), "spawn block must survive explosion");
        assertFalse(affected.contains(noBuildBlock()), "no-build block must survive explosion");
        assertFalse(affected.contains(chest), "public chest must survive explosion");
        assertTrue(affected.contains(wildBlock()), "wilderness block is still destroyed");
    }

    @Test
    void fireSpreadIntoSpawnIsBlocked() {
        BlockSpreadEvent inSpawn = new BlockSpreadEvent(
                spawnBlock(), wildBlock(), spawnBlock().getState());
        server.getPluginManager().callEvent(inSpawn);
        assertTrue(inSpawn.isCancelled(), "spread onto a spawn block must be blocked");

        BlockSpreadEvent inWild = new BlockSpreadEvent(
                wildBlock(), blockAt(501, 64, 500), wildBlock().getState());
        server.getPluginManager().callEvent(inWild);
        assertFalse(inWild.isCancelled(), "spread in wilderness is unaffected");
    }

    @Test
    void nonPublicContainerInSpawnCannotBeOpened() {
        Block chest = spawnBlock();
        chest.setType(Material.CHEST);
        assertTrue(fireInteract(chest),
                "a non-whitelisted container inside spawn must not be openable");
    }

    // ---- Bucket / liquids / fire / entities ------------------------------

    private boolean fireBucketEmpty(Block clicked, BlockFace face) {
        PlayerBucketEmptyEvent event = new PlayerBucketEmptyEvent(
                player, clicked, face, Material.WATER_BUCKET, new ItemStack(Material.WATER_BUCKET));
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void bucketEmptyIsBlockedInProtectedRegionsAndAllowedOutside() {
        // Placing against the block below -> target is the clicked block itself.
        assertTrue(fireBucketEmpty(spawnBlock(), BlockFace.UP), "bucket empty in spawn blocked");
        assertTrue(fireBucketEmpty(noBuildBlock(), BlockFace.UP), "bucket empty in no-build blocked");
        assertFalse(fireBucketEmpty(wildBlock(), BlockFace.UP), "bucket empty in wilderness allowed");
    }

    @Test
    void bucketFillIsBlockedInSpawn() {
        PlayerBucketFillEvent event = new PlayerBucketFillEvent(
                player, spawnBlock(), BlockFace.UP, Material.BUCKET, new ItemStack(Material.BUCKET));
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "bucket fill in spawn must be blocked");
    }

    @Test
    void liquidFlowIntoProtectedRegionIsBlocked() {
        BlockFromToEvent into = new BlockFromToEvent(wildBlock(), spawnBlock());
        server.getPluginManager().callEvent(into);
        assertTrue(into.isCancelled(), "liquid flowing into spawn must be blocked");

        BlockFromToEvent inWild = new BlockFromToEvent(wildBlock(), blockAt(501, 64, 500));
        server.getPluginManager().callEvent(inWild);
        assertFalse(inWild.isCancelled(), "liquid flow in wilderness is unaffected");
    }

    @Test
    void igniteAndBurnInProtectedRegionAreBlocked() {
        BlockIgniteEvent ignite = new BlockIgniteEvent(
                spawnBlock(), BlockIgniteEvent.IgniteCause.SPREAD, wildBlock());
        server.getPluginManager().callEvent(ignite);
        assertTrue(ignite.isCancelled(), "ignite in spawn must be blocked");

        BlockBurnEvent burn = new BlockBurnEvent(noBuildBlock());
        server.getPluginManager().callEvent(burn);
        assertTrue(burn.isCancelled(), "burn in no-build zone must be blocked");
    }

    @Test
    void entityChangeBlockInProtectedRegionIsBlocked() {
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(
                player, spawnBlock(), Material.AIR.createBlockData());
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "enderman-style block change in spawn must be blocked");
    }

    @Test
    void blockExplosionSpecificListAlsoProtectsRegions() {
        List<Block> affected = new ArrayList<>(List.of(spawnBlock(), wildBlock()));
        BlockExplodeEvent event = new BlockExplodeEvent(
                blockAt(600, 64, 600), blockAt(600, 64, 600).getState(),
                affected, 1.0f, ExplosionResult.DESTROY);
        server.getPluginManager().callEvent(event);
        assertFalse(affected.contains(spawnBlock()), "spawn block must survive block explosion");
        assertTrue(affected.contains(wildBlock()), "wilderness block still destroyed");
    }

    // ---- Pistons ---------------------------------------------------------

    private boolean firePistonExtend(Block piston, List<Block> moved, BlockFace dir) {
        BlockPistonExtendEvent event = new BlockPistonExtendEvent(piston, moved, dir);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePistonRetract(Block piston, List<Block> moved, BlockFace dir) {
        BlockPistonRetractEvent event = new BlockPistonRetractEvent(piston, moved, dir);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void pistonPushingBlockIntoSpawnIsBlocked() {
        // Moved block at x=101 (outside), pushed WEST -> destination x=100 (inside spawn).
        Block moved = blockAt(101, 64, 0);
        assertTrue(firePistonExtend(blockAt(102, 64, 0), List.of(moved), BlockFace.WEST),
                "piston pushing a block into spawn must be blocked");
    }

    @Test
    void pistonPullingBlockOutOfSpawnIsBlocked() {
        // Moved block is inside spawn (x=99) -> source protected.
        Block moved = blockAt(99, 64, 0);
        assertTrue(firePistonRetract(blockAt(101, 64, 0), List.of(moved), BlockFace.EAST),
                "piston pulling a block out of spawn must be blocked");
    }

    @Test
    void stickyPistonPullingBlockFromOutsideIntoSpawnIsBlocked() {
        // Moved block at x=101 (outside). On retract it travels opposite to the
        // reported facing (EAST -> WEST), so its destination is x=100 (inside spawn).
        Block moved = blockAt(101, 64, 0);
        assertTrue(firePistonRetract(blockAt(103, 64, 0), List.of(moved), BlockFace.EAST),
                "sticky piston pulling a block into spawn must be blocked");
    }

    @Test
    void pistonRetractInWildernessIsAllowed() {
        Block moved = blockAt(500, 64, 500);
        assertFalse(firePistonRetract(blockAt(498, 64, 500), List.of(moved), BlockFace.WEST),
                "piston retract in wilderness must work normally");
    }

    @Test
    void pistonMovingBlocksInWildernessIsAllowed() {
        Block moved = blockAt(500, 64, 500);
        assertFalse(firePistonExtend(blockAt(499, 64, 500), List.of(moved), BlockFace.EAST),
                "piston in wilderness must work normally");
    }

    @Test
    void pistonCannotMovePublicChest() {
        Block chest = blockAt(0, 65, 0); // public chest
        assertTrue(firePistonRetract(blockAt(2, 65, 0), List.of(chest), BlockFace.EAST),
                "a piston must never move a public chest");
    }

    // ---- Mob spawns ------------------------------------------------------

    private LivingEntity zombieAt(Location loc) {
        // Spawn far outside any region, then relocate, so the spawn call itself
        // is never blocked by our own listener.
        Zombie zombie = loc.getWorld().spawn(new Location(loc.getWorld(), 5000, 64, 5000), Zombie.class);
        zombie.teleport(loc);
        return zombie;
    }

    @Test
    void naturalMobSpawnInSpawnIsBlocked() {
        LivingEntity zombie = zombieAt(new Location(server.getWorld("world"), 0, 200, 0));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(), "natural mob spawn in spawn (any height) must be blocked");
    }

    @Test
    void customMobSpawnInSpawnIsAllowed() {
        LivingEntity zombie = zombieAt(new Location(server.getWorld("world"), 0, 64, 0));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.CUSTOM);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "CUSTOM (plugin/NPC) spawns must be allowed in spawn");
    }

    @Test
    void naturalMobSpawnOutsideSpawnIsAllowed() {
        LivingEntity zombie = zombieAt(new Location(server.getWorld("world"), 500, 64, 500));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "natural spawn in wilderness must be allowed");
    }

    @Test
    void mobSpawnBlockWithDebugEnabledDoesNotThrow() {
        plugin.setRuntimeDebug(true);
        LivingEntity zombie = zombieAt(new Location(server.getWorld("world"), 0, 64, 0));
        CreatureSpawnEvent event = new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }
}

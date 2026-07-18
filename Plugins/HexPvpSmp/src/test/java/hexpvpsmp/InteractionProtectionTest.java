package hexpvpsmp;

import hexpvpsmp.combat.PermissionGate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionProtectionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Block spawnBlock() {
        return server.getWorld("world").getBlockAt(10, 64, 10);
    }

    private Block noBuildBlock() {
        return server.getWorld("world").getBlockAt(10, 64, 150);
    }

    private Block wildBlock() {
        return server.getWorld("world").getBlockAt(500, 64, 500);
    }

    private boolean fireUseBlock(Block block, Material blockType) {
        block.setType(blockType);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.AIR), block, BlockFace.UP,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean fireUseItem(Location at, Material item) {
        return fireUseItem(at, item, false);
    }

    private boolean fireUseItem(Location at, Material item, boolean op) {
        if (op) {
            player.setOp(true);
        }
        player.teleport(at);
        // Right-click a plain (non-container, non-interactable) block while
        // holding the item, so isCancelled() reliably reflects the decision and
        // the region is taken from the clicked block.
        Block ground = at.getBlock();
        ground.setType(Material.STONE);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(item), ground, BlockFace.UP,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    /** Right-click a block while HOLDING an item (for public-chest-with-blocked-item tests). */
    private boolean fireUseBlockHolding(Block block, Material blockType, Material heldItem) {
        block.setType(blockType);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, new ItemStack(heldItem), block, BlockFace.UP,
                EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private Block publicChestBlock() {
        return server.getWorld("world").getBlockAt(0, 65, 0); // configured public chest
    }

    private Location spawn() {
        return new Location(server.getWorld("world"), 0, 64, 0);
    }

    private Location noBuild() {
        return new Location(server.getWorld("world"), 0, 64, 150);
    }

    private Location wild() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    // ---- Block interactables --------------------------------------------

    @Test
    void interactablesBlockedInSpawnAndNoBuild() {
        assertTrue(fireUseBlock(spawnBlock(), Material.OAK_DOOR), "door in spawn blocked");
        assertTrue(fireUseBlock(noBuildBlock(), Material.OAK_FENCE_GATE), "gate in no-build blocked");
        assertTrue(fireUseBlock(spawnBlock(), Material.LEVER), "lever in spawn blocked");
        assertTrue(fireUseBlock(noBuildBlock(), Material.OAK_TRAPDOOR), "trapdoor in no-build blocked");
    }

    @Test
    void interactablesAllowedInWilderness() {
        assertFalse(fireUseBlock(wildBlock(), Material.OAK_DOOR), "door in wilderness allowed");
    }

    @Test
    void craftingTableAllowedEverywhere() {
        assertFalse(fireUseBlock(spawnBlock(), Material.CRAFTING_TABLE),
                "crafting table must be usable in spawn");
    }

    @Test
    void nonPublicContainerBlockedInSpawn() {
        assertTrue(fireUseBlock(spawnBlock(), Material.BARREL), "barrel in spawn blocked");
    }

    @Test
    void publicChestAllowedInSpawn() {
        assertFalse(fireUseBlock(publicChestBlock(), Material.CHEST), "public chest must open in spawn");
    }

    @Test
    void publicChestOpensEvenWithBlockedItemInHand() {
        // Bug: a blocked held item must not stop the public chest from opening.
        assertFalse(fireUseBlockHolding(publicChestBlock(), Material.CHEST, Material.ENDER_EYE),
                "public chest opens even while holding an eye of ender");
        assertFalse(fireUseBlockHolding(publicChestBlock(), Material.CHEST, Material.GOAT_HORN),
                "public chest opens even while holding a goat horn");
    }

    @Test
    void publicTrappedChestOpensInSpawn() {
        // Register the public coordinate as a trapped chest too.
        assertFalse(fireUseBlock(publicChestBlock(), Material.TRAPPED_CHEST),
                "public trapped chest must open in spawn");
    }

    @Test
    void normalChestInSpawnIsBlocked() {
        assertTrue(fireUseBlock(spawnBlock(), Material.CHEST), "non-public chest in spawn blocked");
    }

    @Test
    void craftingTableAllowedInNoBuildAndSpawnWithBlockedItem() {
        assertFalse(fireUseBlock(noBuildBlock(), Material.CRAFTING_TABLE),
                "crafting table must be usable in no-build");
        // A blocked held item must not stop the crafting table from opening.
        assertFalse(fireUseBlockHolding(spawnBlock(), Material.CRAFTING_TABLE, Material.ENDER_EYE),
                "crafting table opens even while holding a blocked item");
    }

    @Test
    void stonecutterAndContainersBlockedInSpawn() {
        assertTrue(fireUseBlock(spawnBlock(), Material.STONECUTTER), "stonecutter in spawn blocked");
        assertTrue(fireUseBlock(spawnBlock(), Material.BARREL), "barrel in spawn blocked");
        assertTrue(fireUseBlock(spawnBlock(), Material.CHEST), "chest in spawn blocked");
    }

    // ---- Eye of Ender / Goat Horn: hard-blocked even for OP/bypass -------

    @Test
    void eyeOfEnderBlockedForNormalAndOpInProtectedRegions() {
        assertTrue(fireUseItem(spawn(), Material.ENDER_EYE), "ender eye in spawn blocked (normal)");
        assertTrue(fireUseItem(noBuild(), Material.ENDER_EYE), "ender eye in no-build blocked (normal)");
        assertTrue(fireUseItem(spawn(), Material.ENDER_EYE, true), "ender eye in spawn blocked (OP)");
        assertTrue(fireUseItem(noBuild(), Material.ENDER_EYE, true), "ender eye in no-build blocked (OP)");
        assertFalse(fireUseItem(wild(), Material.ENDER_EYE), "ender eye in wilderness allowed");
    }

    @Test
    void goatHornBlockedForNormalAndOpInProtectedRegions() {
        assertTrue(fireUseItem(spawn(), Material.GOAT_HORN), "goat horn in spawn blocked (normal)");
        assertTrue(fireUseItem(noBuild(), Material.GOAT_HORN), "goat horn in no-build blocked (normal)");
        assertTrue(fireUseItem(spawn(), Material.GOAT_HORN, true), "goat horn in spawn blocked (OP)");
        assertTrue(fireUseItem(noBuild(), Material.GOAT_HORN, true), "goat horn in no-build blocked (OP)");
        assertFalse(fireUseItem(wild(), Material.GOAT_HORN), "goat horn in wilderness allowed");
    }

    @Test
    void buttonsAllowedByDefaultButLeverAlwaysBlocked() {
        // Buttons are allowed by default now.
        assertFalse(fireUseBlock(spawnBlock(), Material.STONE_BUTTON),
                "button allowed in spawn by default");
        assertFalse(fireUseBlock(noBuildBlock(), Material.STONE_BUTTON),
                "button allowed in no-build by default");
        // Levers stay blocked regardless.
        assertTrue(fireUseBlock(spawnBlock(), Material.LEVER), "lever always blocked");
    }

    @Test
    void buttonsCanBeBlockedByConfig() {
        plugin.getConfig().set("protection.interactions.block-buttons", true);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(fireUseBlock(spawnBlock(), Material.STONE_BUTTON),
                "button blocked when block-buttons=true");
    }

    @Test
    void bypassPlayerCanInteractByDefault() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        assertFalse(fireUseBlock(spawnBlock(), Material.OAK_DOOR),
                "bypass player may use interactables");
    }

    @Test
    void bypassInteractCanBeDisabledForOp() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        plugin.getConfig().set("protection.bypass.interact", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(fireUseBlock(spawnBlock(), Material.OAK_DOOR),
                "bypass.interact=false enforces the rule even for bypass players");
    }

    // ---- Item use --------------------------------------------------------

    @Test
    void terrainItemsBlockedInSpawnAndNoBuild() {
        assertTrue(fireUseItem(spawn(), Material.OAK_BOAT), "boat in spawn blocked");
        assertTrue(fireUseItem(noBuild(), Material.OAK_BOAT), "boat in no-build blocked");
        assertTrue(fireUseItem(spawn(), Material.ENDER_PEARL), "pearl in spawn blocked");
        assertTrue(fireUseItem(noBuild(), Material.BONE_MEAL), "bone meal in no-build blocked");
        assertFalse(fireUseItem(wild(), Material.OAK_BOAT), "boat in wilderness allowed");
    }

    @Test
    void combatItemsBlockedInSpawnAlways() {
        assertTrue(fireUseItem(spawn(), Material.SNOWBALL), "snowball in spawn blocked");
        assertTrue(fireUseItem(spawn(), Material.BOW), "bow in spawn blocked");
        assertTrue(fireUseItem(spawn(), Material.SPLASH_POTION), "splash potion in spawn blocked");
    }

    @Test
    void combatItemsAllowedInNoBuildByDefault() {
        assertFalse(fireUseItem(noBuild(), Material.SNOWBALL),
                "combat item allowed in no-build by default");
    }

    @Test
    void combatItemsBlockedInNoBuildWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(fireUseItem(noBuild(), Material.SNOWBALL),
                "combat item blocked in no-build when block-pvp-in-no-build=true");
    }

    @Test
    void ordinaryItemsAlwaysAllowed() {
        assertFalse(fireUseItem(spawn(), Material.DIAMOND_SWORD), "sword use allowed in spawn");
    }
}

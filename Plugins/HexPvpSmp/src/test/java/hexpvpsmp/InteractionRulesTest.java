package hexpvpsmp;

import hexpvpsmp.protection.InteractionRules;
import hexpvpsmp.protection.InteractionRules.ItemCategory;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data-driven rule catalogue. Uses MockBukkit so Bukkit tags resolve. */
class InteractionRulesTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void interactablesAreBlocked() {
        for (Material m : new Material[]{
                Material.OAK_FENCE_GATE, Material.OAK_DOOR, Material.IRON_DOOR,
                Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR, Material.LEVER,
                Material.FLOWER_POT, Material.POTTED_CACTUS, Material.WHITE_CANDLE,
                Material.SWEET_BERRY_BUSH, Material.CAVE_VINES, Material.OAK_SIGN,
                Material.OAK_WALL_SIGN, Material.OAK_HANGING_SIGN, Material.JUKEBOX,
                Material.STONECUTTER}) {
            assertTrue(InteractionRules.isProtectedInteractable(m, true),
                    m + " must be a protected interactable");
        }
    }

    @Test
    void buttonsAreConfigurable() {
        assertTrue(InteractionRules.isProtectedInteractable(Material.STONE_BUTTON, true),
                "buttons blocked when blockButtons=true");
        assertFalse(InteractionRules.isProtectedInteractable(Material.STONE_BUTTON, false),
                "buttons allowed when blockButtons=false");
    }

    @Test
    void craftingTableIsNeverInteractableNorContainer() {
        assertFalse(InteractionRules.isProtectedInteractable(Material.CRAFTING_TABLE, true));
        assertFalse(InteractionRules.isBlockedContainer(Material.CRAFTING_TABLE));
        assertTrue(InteractionRules.isAlwaysAllowed(Material.CRAFTING_TABLE));
        assertFalse(InteractionRules.isBlockedContainer(Material.ENDER_CHEST));
        assertTrue(InteractionRules.isAlwaysAllowed(Material.ENDER_CHEST));
    }

    @Test
    void containersAreBlockedChestsAndFriends() {
        for (Material m : new Material[]{
                Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL, Material.HOPPER,
                Material.DROPPER, Material.DISPENSER, Material.FURNACE, Material.BLAST_FURNACE,
                Material.SMOKER, Material.BREWING_STAND, Material.SHULKER_BOX,
                Material.DECORATED_POT}) {
            assertTrue(InteractionRules.isBlockedContainer(m), m + " must be a blocked container");
        }
    }

    @Test
    void terrainItemsAreClassified() {
        for (Material m : new Material[]{
                Material.OAK_BOAT, Material.ACACIA_CHEST_BOAT, Material.BAMBOO_RAFT,
                Material.MINECART, Material.CHEST_MINECART, Material.HOPPER_MINECART,
                Material.BONE_MEAL, Material.ENDER_EYE, Material.EGG, Material.ENDER_PEARL,
                Material.GOAT_HORN}) {
            assertEquals(ItemCategory.TERRAIN, InteractionRules.itemCategory(m),
                    m + " must be a TERRAIN item");
        }
    }

    @Test
    void combatItemsAreClassified() {
        for (Material m : new Material[]{
                Material.SNOWBALL, Material.WIND_CHARGE,
                Material.FIREWORK_ROCKET, Material.SPLASH_POTION, Material.LINGERING_POTION,
                Material.BOW, Material.CROSSBOW}) {
            assertEquals(ItemCategory.COMBAT, InteractionRules.itemCategory(m),
                    m + " must be a COMBAT item");
        }
    }

    @Test
    void craftingTableIsAlwaysAllowed() {
        assertTrue(InteractionRules.isAlwaysAllowed(Material.CRAFTING_TABLE));
        assertFalse(InteractionRules.isAlwaysAllowed(Material.CHEST));
        assertFalse(InteractionRules.isAlwaysAllowed(Material.BARREL));
    }

    @Test
    void ordinaryItemsAreNotRestricted() {
        assertEquals(ItemCategory.NONE, InteractionRules.itemCategory(Material.DIAMOND_SWORD));
        assertEquals(ItemCategory.NONE, InteractionRules.itemCategory(Material.AIR));
        assertEquals(ItemCategory.NONE, InteractionRules.itemCategory(Material.BREAD));
    }
}

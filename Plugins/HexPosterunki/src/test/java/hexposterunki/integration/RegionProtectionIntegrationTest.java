package hexposterunki.integration;

import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 6 regression through the real event pipeline: the registered
 * {@code RegionProtectionListener} handles genuine Bukkit events and cooperates with the engine's
 * loot-ownership rules.
 */
class RegionProtectionIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private OutpostTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    /**
     * The configured loot container of the test outpost.
     *
     * <p>A hopper rather than a chest: MockBukkit implements {@code HopperState#getInventory} but
     * not the chest or barrel equivalents. Production accepts any {@code Container} block, so the
     * protection logic under test is identical.
     */
    private Inventory lootContainerInside() {
        Location location = harness.inside(10, 64, 10);
        location.getBlock().setType(Material.HOPPER);
        return ((org.bukkit.block.Container) location.getBlock().getState()).getInventory();
    }

    /**
     * Opens an inventory the way a player would and reports the plugin's verdict.
     *
     * <p>{@code openInventory} fires the real {@link InventoryOpenEvent} itself, so the verdict is
     * observed with a MONITOR listener rather than by building a second event afterwards.
     */
    private boolean opensSuccessfully(Player player, Inventory inventory) {
        OpenVerdict verdict = new OpenVerdict();
        harness.server.getPluginManager().registerEvents(verdict, harness.plugin);
        try {
            player.openInventory(inventory);
        } finally {
            InventoryOpenEvent.getHandlerList().unregister(verdict);
        }
        assertTrue(verdict.seen, "zdarzenie otwarcia musi dotrzeć do listenerów");
        return !verdict.cancelled;
    }

    /** Observes the final verdict after the plugin's HIGH-priority handler ran. */
    private static final class OpenVerdict implements org.bukkit.event.Listener {
        private boolean seen;
        private boolean cancelled;

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR,
                ignoreCancelled = false)
        public void onOpen(InventoryOpenEvent event) {
            seen = true;
            cancelled = event.isCancelled();
        }
    }

    private Inventory hopperInside() {
        Location location = harness.inside(11, 64, 10);
        location.getBlock().setType(Material.HOPPER);
        return ((org.bukkit.block.Container) location.getBlock().getState()).getInventory();
    }

    @Test
    void automatedHopperTransferIntoAProtectedContainerIsBlocked() {
        Inventory hopper = hopperInside();
        Inventory chest = lootContainerInside();

        InventoryMoveItemEvent event = new InventoryMoveItemEvent(hopper,
                new ItemStack(Material.DIAMOND, 1), chest, true);
        harness.server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(),
                "automatyczny transfer do chronionego kontenera musi zostać zablokowany");
    }

    @Test
    void automatedTransferOutOfAProtectedContainerIsBlocked() {
        Inventory chest = lootContainerInside();
        // The destination sits outside the region: the transfer still crosses the border.
        Location outside = harness.outside();
        outside.getBlock().setType(Material.HOPPER);
        Inventory target = ((org.bukkit.block.Container) outside.getBlock().getState()).getInventory();

        InventoryMoveItemEvent event = new InventoryMoveItemEvent(chest,
                new ItemStack(Material.DIAMOND, 1), target, true);
        harness.server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(),
                "transfer poza granicę regionu też musi zostać zablokowany");
    }

    @Test
    void anArmorStandInsideTheRegionCannotBeDamaged() {
        ArmorStand stand = harness.world.spawn(harness.inside(12, 64, 12), ArmorStand.class);
        Player attacker = harness.server.addPlayer("Niszczyciel");

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 10.0);
        harness.server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "stojak na zbroję musi być chroniony także przed atakiem");
    }

    @Test
    void anArmorStandOutsideTheRegionStaysUnprotected() {
        ArmorStand stand = harness.world.spawn(harness.outside(), ArmorStand.class);
        Player attacker = harness.server.addPlayer("Niszczyciel");

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 10.0);
        harness.server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "poza regionem nic nie blokujemy");
    }

    @Test
    void aLockedLootContainerCannotBeOpened() {
        Player player = harness.server.addPlayer("Ciekawski");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        Inventory chest = lootContainerInside();

        assertFalse(opensSuccessfully(player, chest),
                "skrzynia z lootem jest zamknięta zanim posterunek zostanie oczyszczony");
    }

    @Test
    void anUnlockedLootContainerBelongsToTheControllingTownOnly() throws Exception {
        Player owner = harness.server.addPlayer("Zwyciezca");
        harness.towns.assign(owner.getUniqueId(), TOWN, "Rycerze");
        owner.teleport(harness.inside(8, 64, 8));

        Player outsider = harness.server.addPlayer("Obcy");
        harness.towns.assign(outsider.getUniqueId(), OTHER_TOWN, "Najeźdźcy");
        outsider.teleport(harness.inside(9, 64, 9));

        Player townless = harness.server.addPlayer("Bezdomny");
        townless.teleport(harness.inside(7, 64, 7));

        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.settle();

        Inventory chest = lootContainerInside();
        // The container is unlocked for this run, as it would be after the waves are cleared.
        harness.loot.markUnlocked(hexposterunki.engine.LootService.containerId("fort", "skrzynia"));

        assertTrue(opensSuccessfully(owner, chest), "kontrolująca drużyna może otworzyć loot");
        assertFalse(opensSuccessfully(outsider, chest), "obca drużyna nie dostaje łupów");
        assertFalse(opensSuccessfully(townless, chest), "gracz bez drużyny nie dostaje łupów");
    }

    @Test
    void containersThatAreNotConfiguredLootStayClosedEntirely() {
        Player player = harness.server.addPlayer("Ciekawski");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");

        Location location = harness.inside(20, 64, 20);
        location.getBlock().setType(Material.HOPPER);
        Inventory other = ((org.bukkit.block.Container) location.getBlock().getState()).getInventory();

        assertFalse(opensSuccessfully(player, other), "zwykłe kontenery w regionie są zawsze chronione");
    }

    @Test
    void blockBreakingInsideTheRegionIsBlocked() {
        Player player = harness.server.addPlayer("Kopacz");
        Location location = harness.inside(15, 64, 15);
        location.getBlock().setType(Material.STONE);

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(location.getBlock(), player);
        harness.server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void protectionAppliesEvenWhileNoEncounterIsRunning() {
        // No run was started at all - the region is still permanently protected.
        assertFalse(harness.engine.phase().isLive());

        Player player = harness.server.addPlayer("Kopacz");
        Location location = harness.inside(15, 64, 15);
        location.getBlock().setType(Material.STONE);

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(location.getBlock(), player);
        harness.server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "ochrona działa niezależnie od trwania eventu");
    }
}

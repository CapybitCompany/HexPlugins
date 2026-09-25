package hexposterunki.integration;

import hexposterunki.config.LootConfig;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.config.RewardsConfig;
import hexposterunki.domain.BossStatus;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.support.FakeBossAdapter;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full review, finding 1: a grace-period wipe of an abandoned boss fight never turns into a victory -
 * neither when stopping the boss fires its death synchronously nor when the death arrives later. Loot
 * filling on completion and top rewards are enabled, so a false victory would leave visible traces.
 */
class GraceWipeIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** Shortest grace period (clamped to one second), one required kill, rewards and completion loot. */
    private static PosterunkiConfig wipeConfig(PosterunkiConfig c) {
        PosterunkiConfig.Timing t = c.timing();
        LootConfig loot = new LootConfig(true, LootConfig.FillPhase.COMPLETED, LootConfig.ResetMode.CLEAR,
                "standard", null, Map.of("standard", new LootConfig.LootTable(1,
                List.of(new LootConfig.LootEntry("DIAMOND", 1, 1, 1)))), Map.of());
        RewardsConfig rewards = new RewardsConfig(true, 5, 3, Map.of(1, new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("EMERALD", 1, null, List.of())), List.of())));
        return new PosterunkiConfig(c.enabled(), c.debug(), new PosterunkiConfig.Timing(t.activeWindowSeconds(),
                t.cooldownSeconds(), t.preparationSeconds(), t.waveDelaySeconds(), 0L, t.lootSeconds(),
                t.tickIntervalTicks(), t.snapshotIntervalSeconds()), 1, c.avoidImmediateRepeat(), c.waves(),
                c.boss(), c.towns(), c.protection(), c.ui(), rewards, loot);
    }

    private static Player fighter(OutpostTestHarness harness) {
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        return player;
    }

    private static void clearWaveIntoBoss(OutpostTestHarness harness, Player player) throws Exception {
        for (Entity entity : harness.world.getEntities()) {
            if (harness.engine.liveMobs().containsKey(entity.getUniqueId())) {
                harness.engine.onRunMobDeath((LivingEntity) entity, player);
                entity.remove();
            }
        }
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.BOSS, harness.engine.phase(), harness.engine.state().failureReason());
    }

    private static void startIntoBoss(OutpostTestHarness harness, Player player) throws Exception {
        harness.setConfig(wipeConfig(harness.currentConfig()));
        harness.world.getBlockAt(10, 64, 10).setType(Material.CHEST);
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
        clearWaveIntoBoss(harness, player);
    }

    /** Everyone leaves; the grace period expires on the next running tick. */
    private static void abandonUntilWiped(OutpostTestHarness harness, Player player) throws Exception {
        player.teleport(harness.outside());
        harness.tick();
        Thread.sleep(1_100L);
        harness.tick();
        harness.settle();
    }

    private static void assertNoVictoryTraces(OutpostTestHarness harness, String runId, String bossId) {
        assertEquals(RunPhase.WAITING, harness.engine.phase(), harness.engine.state().failureReason());
        assertEquals(1, harness.engine.state().wave());
        assertEquals(runId, harness.engine.state().runId().orElseThrow(), "ten sam run, brak nowej rundy");
        assertEquals(bossId, harness.engine.state().bossId().orElseThrow(), "wylosowany boss zostaje");
        assertEquals(BossStatus.PENDING, harness.engine.state().bossStatus(), "boss nie jest uznany za pokonanego");
        assertEquals(harness.outpost, harness.engine.activeOutpost().orElseThrow(), "ta sama lokalizacja");
        assertTrue(harness.engine.state().controllingTown().isEmpty());
        assertTrue(harness.engine.liveMobs().isEmpty());
        assertFalse(harness.rewards.ledger().isFrozen(runId), "porzucona walka nie zamraża nagród");
        assertTrue(harness.persistence.repository().loadClaims(RewardClaim.Status.CLAIMED).isEmpty());
        assertTrue(harness.persistence.repository().loadCompletionStats(runId).isEmpty(), "brak statystyk zwycięstwa");
        assertTrue(harness.persistence.repository().recentAudit(100).stream().noneMatch(line -> line.contains("COMPLETED")),
                "porzucona walka nie jest ogłoszona jako wygrana");
        Chest chest = (Chest) harness.world.getBlockAt(10, 64, 10).getState();
        assertFalse(chest.getInventory().contains(Material.DIAMOND), "brak łupów zwycięstwa");
        assertFalse(harness.loot.isUnlocked("fort", "skrzynia"));
        assertEquals(RunPhase.WAITING, harness.persistence.repository().loadAll().run().phase());
    }

    @Test
    void aBossDeathFiredByStoppingTheBossDuringTheWipeIsNoVictory() throws Exception {
        FakeBossAdapter boss = new FakeBossAdapter();
        boss.killOnStop(true);
        try (OutpostTestHarness harness = OutpostTestHarness.withBoss(boss)) {
            Player player = fighter(harness);
            startIntoBoss(harness, player);
            String runId = harness.engine.state().runId().orElseThrow();
            String bossId = harness.engine.state().bossId().orElseThrow();
            AtomicInteger operationNotices = new AtomicInteger();
            harness.engine.setOperationListener(operationNotices::incrementAndGet);

            abandonUntilWiped(harness, player);

            assertNoVictoryTraces(harness, runId, bossId);
            assertTrue(harness.engine.operational());
            assertEquals(EngineMode.RUNNING, harness.engine.mode());
            assertEquals(0, operationNotices.get(), "wipe nie zmienia stanu pracy eventu");
        }
    }

    @Test
    void aLateBossDeathAfterTheWipeIsNoVictoryAndTheSameBossReturns() throws Exception {
        FakeBossAdapter boss = new FakeBossAdapter();
        try (OutpostTestHarness harness = OutpostTestHarness.withBoss(boss)) {
            Player player = fighter(harness);
            startIntoBoss(harness, player);
            String runId = harness.engine.state().runId().orElseThrow();
            String bossId = harness.engine.state().bossId().orElseThrow();
            Zombie bossEntity = (Zombie) org.bukkit.Bukkit.getEntity(harness.engine.state().bossEntityId().orElseThrow());

            abandonUntilWiped(harness, player);
            // The boss engine reports the death of the removed boss one tick later.
            harness.server.getPluginManager().callEvent(new EntityDeathEvent(bossEntity,
                    DamageSource.builder(DamageType.GENERIC_KILL).build(), List.of()));
            harness.tick();
            harness.settle();

            assertNoVictoryTraces(harness, runId, bossId);

            // The town returns: the same run and the same boss roll lead into a new boss fight.
            player.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
            player.teleport(harness.inside(8, 64, 8));
            harness.tick();
            harness.tick();
            harness.settle();
            assertEquals(RunPhase.WAVES, harness.engine.phase());
            clearWaveIntoBoss(harness, player);
            assertEquals(runId, harness.engine.state().runId().orElseThrow());
            assertEquals(bossId, harness.engine.state().bossId().orElseThrow());
            assertEquals(2, boss.spawns(), "ten sam boss pojawia się ponownie po powrocie");
        }
    }
}

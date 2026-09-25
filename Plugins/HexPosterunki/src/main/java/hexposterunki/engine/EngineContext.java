package hexposterunki.engine;

import hexcustommobs.api.CustomMobsApi;
import hexposterunki.boss.BossEngineAdapter;
import hexposterunki.boss.BossRoller;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.mobs.RunTags;
import hexposterunki.persistence.PersistenceService;
import hexposterunki.region.RegionIndex;
import hexposterunki.rewards.RewardService;
import hexposterunki.selection.OutpostSelector;
import hexposterunki.towns.TownsAdapter;
import hexposterunki.ui.DisplayService;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.util.RandomSource;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;
import java.util.logging.Logger;

/** Collaborators of {@link OutpostEngine}, bundled so the engine keeps a readable constructor. */
public record EngineContext(
        Plugin plugin,
        Logger logger,
        Supplier<PosterunkiConfig> config,
        Supplier<OutpostCatalog> catalog,
        PersistenceService persistence,
        PosterunkiUi ui,
        DisplayService displays,
        RegionIndex regions,
        RunTags tags,
        TownsAdapter towns,
        CustomMobsApi customMobs,
        BossEngineAdapter bossAdapter,
        BossRoller bossRoller,
        OutpostSelector selector,
        RandomSource random,
        LootService loot,
        BlockStateService blockStates,
        ChunkTicketService chunkTickets,
        RewardService rewards,
        WaveSpawner waveSpawner
) {
}

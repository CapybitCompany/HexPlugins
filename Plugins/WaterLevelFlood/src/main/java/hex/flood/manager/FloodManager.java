package hex.flood.manager;

import hex.flood.WaterLevelFloodPlugin;
import hex.flood.config.FloodConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Zarzadza procesem zalewania obszaru woda.
 *
 * Kluczowe zasady wydajnosci:
 * - Brak nakladania wielu taskow warstw jednoczesnie.
 * - Przetwarzanie w paczkach blokow na tick.
 * - Brak wymuszania ladowania chunkow (pomijamy nieladowane chunki).
 */
public class FloodManager {

    private static final BlockData WATER_DATA = Material.WATER.createBlockData();
    private static final BlockData AIR_DATA = Material.AIR.createBlockData();

    private final WaterLevelFloodPlugin plugin;
    private FloodConfig config;

    private BukkitTask riseTask;
    private BukkitTask fillTask;   // initial fill/reset
    private BukkitTask layerTask;  // pojedyncza warstwa podczas rising

    private int currentWaterLevel;
    private boolean running = false;
    private boolean busy = false;

    private final Set<Long> chunkTickets = new HashSet<>();

    public FloodManager(WaterLevelFloodPlugin plugin, FloodConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.currentWaterLevel = config.getStartWaterLevel();
    }

    public void updateConfig(FloodConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isBusy() {
        return busy;
    }

    public int getCurrentWaterLevel() {
        return currentWaterLevel;
    }

    public int getTargetWaterLevel() {
        return config.getTargetWaterLevel();
    }

    /**
     * Rozpoczyna event zalewania.
     * Najpierw wypełnia bloki poniżej start-level wodą,
     * a potem zaczyna cykliczne podnoszenie poziomu.
     */
    public void start() {
        if (running || busy) return;

        running = true;
        currentWaterLevel = Math.max(config.getYMin(), Math.min(config.getStartWaterLevel(), config.getYMax()));

        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            running = false;
            plugin.getLogger().warning("Flood start aborted: world not found: " + config.getWorldName());
            return;
        }

        preloadRegionChunks(world);

        plugin.getLogger().info("Start flood: level=" + currentWaterLevel
                + ", target=" + config.getTargetWaterLevel()
                + ", duration=" + config.getDurationSeconds() + "s");

        // Etap 1: Wypełnij początkowy poziom wody (wszystko od yMin do startWaterLevel)
        fillRegion(config.getYMin(), currentWaterLevel, true, this::startRising);
    }

    /**
     * Zatrzymuje podnoszenie wody (nie resetuje).
     */
    public void stop() {
        running = false;
        cancelTasks();

        World world = Bukkit.getWorld(config.getWorldName());
        if (world != null) {
            releaseRegionChunks(world);
        }

        plugin.getLogger().info("Flood zatrzymany na Y=" + currentWaterLevel);
    }

    /**
     * Resetuje - zamienia wodę z powrotem na powietrze.
     */
    public void reset(Runnable onComplete) {
        if (busy) return;

        stop();

        World world = Bukkit.getWorld(config.getWorldName());
        if (world != null) {
            preloadRegionChunks(world);
        }

        plugin.getLogger().info("Resetuję flood - usuwam wodę od Y=" + currentWaterLevel + " do Y=" + config.getYMin());

        // Usuwamy wodę od góry do dołu (odwrotnie niż stawianie)
        fillRegion(config.getYMin(), currentWaterLevel, false, () -> {
            currentWaterLevel = Math.max(config.getYMin(), Math.min(config.getStartWaterLevel(), config.getYMax()));
            if (onComplete != null) {
                onComplete.run();
            }
            if (world != null) {
                releaseRegionChunks(world);
            }
        });
    }

    /**
     * Rozpoczyna cykliczne podnoszenie poziomu wody.
     * Oblicza interwał między warstwami na podstawie konfiguracji.
     */
    private void startRising() {
        int targetLevel = Math.min(config.getTargetWaterLevel(), config.getYMax());
        if (currentWaterLevel >= targetLevel) {
            running = false;
            return;
        }

        int totalRiseTicks = Math.max(20, config.getDurationSeconds() * 20);
        int layersToRise = Math.max(1, targetLevel - currentWaterLevel);
        // Ile tików między warstwami - minimum 1
        int ticksPerLayer = Math.max(1, totalRiseTicks / layersToRise);

        plugin.getLogger().info("Rising layers=" + layersToRise + ", intervalTicks=" + ticksPerLayer);

        riseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!running) {
                cancelTasks();
                return;
            }

            if (currentWaterLevel >= targetLevel) {
                cancelRiseTask();
                return;
            }

            // Nie uruchamiaj nowej warstwy, dopoki poprzednia sie nie skonczy.
            if (layerTask != null) {
                return;
            }

            currentWaterLevel++;
            publishWaterLevelChanged(currentWaterLevel - 1, currentWaterLevel);
            fillSingleLayerAsync(currentWaterLevel, true, null);
        }, ticksPerLayer, ticksPerLayer);
    }

    private void publishWaterLevelChanged(int previousLevel, int newLevel) {
        try {
            Class<?> hexApiClass = Class.forName("hex.core.api.HexApi");
            var registration = Bukkit.getServicesManager().getRegistration((Class) hexApiClass);
            if (registration == null) {
                return;
            }

            Object apiProvider = registration.getProvider();
            Class<?> messageBusClass = Class.forName("hex.core.api.messaging.HexMessageBus");
            Optional<?> busOpt = (Optional<?>) apiProvider.getClass()
                    .getMethod("service", Class.class)
                    .invoke(apiProvider, messageBusClass);
            if (busOpt.isEmpty()) {
                return;
            }

            Object bus = busOpt.get();

            Class<?> messageDataClass = Class.forName("hex.core.api.messaging.HexMessageData");
            Object dataBuilder = messageDataClass.getMethod("builder").invoke(null);
            dataBuilder = dataBuilder.getClass().getMethod("put", String.class, String.class)
                    .invoke(dataBuilder, "world", config.getWorldName());
            dataBuilder = dataBuilder.getClass().getMethod("put", String.class, int.class)
                    .invoke(dataBuilder, "previous_level", previousLevel);
            dataBuilder = dataBuilder.getClass().getMethod("put", String.class, int.class)
                    .invoke(dataBuilder, "current_level", newLevel);
            Object messageData = dataBuilder.getClass().getMethod("build").invoke(dataBuilder);

            Class<?> messageClass = Class.forName("hex.core.api.messaging.HexMessage");
            Object message = messageClass.getMethod("of", String.class, String.class, messageDataClass)
                    .invoke(null, "flood.water-level", "WaterLevelFlood", messageData);

            bus.getClass().getMethod("publish", messageClass).invoke(bus, message);
        } catch (Throwable ignored) {
            // HexCore message bus is optional; flood still works standalone.
        }
    }

    /**
     * Wypełnia pojedynczą warstwę Y wodą lub powietrzem asynchronicznie.
     * Używane podczas cyklu podnoszenia.
     */
    private void fillSingleLayerAsync(int y, boolean water, Runnable onComplete) {
        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        int clampedY = Math.max(config.getYMin(), Math.min(y, config.getYMax()));
        Set<Material> replaceable = config.getReplaceableBlocks();
        BlockData targetData = water ? WATER_DATA : AIR_DATA;

        final int[] pos = { config.getX1(), config.getZ1() };

        layerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int processed = 0;
            int maxPerTick = getSafeBlocksPerTick();

            while (processed < maxPerTick) {
                int cx = pos[0];
                int cz = pos[1];

                if (cx > config.getX2()) {
                    if (layerTask != null) {
                        layerTask.cancel();
                        layerTask = null;
                    }
                    if (onComplete != null) {
                        Bukkit.getScheduler().runTask(plugin, onComplete);
                    }
                    return;
                }

                setBlockIfReplaceable(world, cx, clampedY, cz, targetData, replaceable);
                processed++;

                cz++;
                if (cz > config.getZ2()) {
                    cz = config.getZ1();
                    cx++;
                }

                pos[0] = cx;
                pos[1] = cz;
            }
        }, 0L, 1L);
    }

    /**
     * Wypełnia region warstwami od yFrom do yTo (włącznie).
     * Używane do wypełniania początkowego i resetowania.
     * Rozłożone w czasie żeby nie lagować.
     */
    private void fillRegion(int yFrom, int yTo, boolean water, Runnable onComplete) {
        busy = true;
        World world = Bukkit.getWorld(config.getWorldName());
        if (world == null) {
            busy = false;
            if (onComplete != null) onComplete.run();
            return;
        }

        Set<Material> replaceable = config.getReplaceableBlocks();
        BlockData targetData = water ? WATER_DATA : AIR_DATA;

        // Iterator po warstwach i blokach wewnątrz warstwy
        // Jeśli stawiamy - od dołu do góry; jeśli usuwamy - od góry do dołu
        final int startY = water ? yFrom : yTo;
        final int endY = water ? yTo : yFrom;
        final int step = water ? 1 : -1;

        final int[] state = { startY, config.getX1(), config.getZ1() }; // [currentY, currentX, currentZ]

        fillTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int processed = 0;
            int maxPerTick = getSafeBlocksPerTick();

            while (processed < maxPerTick) {
                int cy = state[0];
                int cx = state[1];
                int cz = state[2];

                // Sprawdź czy skończyliśmy
                if ((step > 0 && cy > endY) || (step < 0 && cy < endY)) {
                    // Zakończono
                    fillTask.cancel();
                    fillTask = null;
                    busy = false;
                    if (onComplete != null) {
                        Bukkit.getScheduler().runTask(plugin, onComplete);
                    }
                    return;
                }

                setBlockIfReplaceable(world, cx, cy, cz, targetData, replaceable);
                processed++;

                // Przejdź do następnego bloku
                cz++;
                if (cz > config.getZ2()) {
                    cz = config.getZ1();
                    cx++;
                    if (cx > config.getX2()) {
                        cx = config.getX1();
                        cy += step;
                    }
                }
                state[0] = cy;
                state[1] = cx;
                state[2] = cz;
            }
        }, 1L, 1L);
    }

    private int getSafeBlocksPerTick() {
        // Ograniczenie bezpieczenstwa: zbyt duze wartosci powoduja watchdog timeout.
        return Math.max(200, Math.min(config.getBlocksPerTick(), 2000));
    }

    /**
     * Stawia blok jeśli obecny materiał jest w liście replaceable.
     * Kluczowa optymalizacja: nie wywołuje physics update (false w setBlockData).
     */
    private void setBlockIfReplaceable(World world, int x, int y, int z, BlockData data, Set<Material> replaceable) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        // Region chunki sa ticketowane na czas flooda; to dodatkowa asekuracja.
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        Block block = world.getBlockAt(x, y, z);
        if (replaceable.contains(block.getType())) {
            block.setBlockData(data, false);
        }
    }

    private void preloadRegionChunks(World world) {
        int minChunkX = config.getX1() >> 4;
        int maxChunkX = config.getX2() >> 4;
        int minChunkZ = config.getZ1() >> 4;
        int maxChunkZ = config.getZ2() >> 4;

        int loaded = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz); // laduje chunk synchronicznie
                if (world.addPluginChunkTicket(cx, cz, plugin)) {
                    chunkTickets.add(chunkKey(cx, cz));
                }
                loaded++;
            }
        }

        plugin.getLogger().info("Flood chunk preload complete: " + loaded + " chunks.");
    }

    private void releaseRegionChunks(World world) {
        for (long key : chunkTickets) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            world.removePluginChunkTicket(cx, cz, plugin);
        }
        chunkTickets.clear();
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * Sprawdza czy dany punkt XYZ jest w strefie zalanej (pod aktualnym poziomem wody).
     */
    public boolean isInFloodZone(int x, int y, int z) {
        return running
                && x >= config.getX1() && x <= config.getX2()
                && z >= config.getZ1() && z <= config.getZ2()
                && y <= currentWaterLevel
                && y >= config.getYMin();
    }

    /**
     * Sprawdza czy pozycja jest w regionie flood (niezależnie od poziomu wody).
     */
    public boolean isInRegion(int x, int z) {
        return x >= config.getX1() && x <= config.getX2()
                && z >= config.getZ1() && z <= config.getZ2();
    }

    private void cancelTasks() {
        cancelRiseTask();

        if (fillTask != null) {
            fillTask.cancel();
            fillTask = null;
        }

        if (layerTask != null) {
            layerTask.cancel();
            layerTask = null;
        }

        busy = false;
    }

    private void cancelRiseTask() {
        if (riseTask != null) {
            riseTask.cancel();
            riseTask = null;
        }
    }
}


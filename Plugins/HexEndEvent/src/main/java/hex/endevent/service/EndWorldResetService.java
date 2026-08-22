package hex.endevent.service;

import hex.endevent.config.EndEventConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class EndWorldResetService {
    public record ResetResult(boolean success, String error, World world) {
        public static ResetResult ok(World world) { return new ResetResult(true, "", world); }
        public static ResetResult fail(String error) { return new ResetResult(false, error, null); }
    }

    private final Plugin plugin;
    private volatile EndEventConfig config;

    public EndWorldResetService(Plugin plugin, EndEventConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reload(EndEventConfig config) {
        this.config = config;
    }

    public CompletableFuture<ResetResult> prepare(long seed) {
        CompletableFuture<ResetResult> future = new CompletableFuture<>();
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> beginPrepare(seed, future));
        } else {
            beginPrepare(seed, future);
        }
        return future;
    }

    private void beginPrepare(long seed, CompletableFuture<ResetResult> future) {
        EndEventConfig cfg = config;
        String safetyError = validateWorldPath(cfg);
        if (safetyError != null) {
            future.complete(ResetResult.fail(safetyError));
            return;
        }
        World returnWorld = Bukkit.getWorld(cfg.returnWorld());
        if (returnWorld == null || returnWorld.getEnvironment() != World.Environment.NORMAL) {
            future.complete(ResetResult.fail("Return world '" + cfg.returnWorld() + "' nie jest zaladowanym swiatem NORMAL"));
            return;
        }

        World end = Bukkit.getWorld(cfg.endWorld());
        if (end != null && end.getEnvironment() != World.Environment.THE_END) {
            future.complete(ResetResult.fail("Swiat '" + cfg.endWorld() + "' jest zaladowany, ale nie jest THE_END"));
            return;
        }
        if (end != null && !evictPlayers(end, returnWorld)) {
            future.complete(ResetResult.fail("Nie udalo sie ewakuowac wszystkich graczy przed resetem Endu"));
            return;
        }

        if (!cfg.resetBeforeEachEvent()) {
            World loaded = ensureWorldLoaded(seed, true);
            future.complete(loaded == null ? ResetResult.fail("Nie udalo sie zaladowac/stworzyc Endu") : ResetResult.ok(loaded));
            return;
        }

        if (end != null && !Bukkit.unloadWorld(end, false)) {
            future.complete(ResetResult.fail("Bukkit.unloadWorld zwrocil false; katalog Endu NIE zostal usuniety"));
            return;
        }

        Path worldPath = managedWorldPath(cfg);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteRecursively(worldPath);
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> future.complete(ResetResult.fail("Blad usuwania starego Endu: " + rootMessage(ex))));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                World created = ensureWorldLoaded(seed, true);
                if (created == null || created.getEnvironment() != World.Environment.THE_END) {
                    future.complete(ResetResult.fail("Nie udalo sie utworzyc swiezego swiata THE_END"));
                    return;
                }
                future.complete(ResetResult.ok(created));
            });
        });
    }

    public boolean ensurePreparedWorldLoaded(long seed) {
        EndEventConfig cfg = config;
        World loaded = Bukkit.getWorld(cfg.endWorld());
        if (loaded != null) return loaded.getEnvironment() == World.Environment.THE_END;
        Path path = managedWorldPath(cfg);
        if (!Files.isDirectory(path)) return false;
        World world = ensureWorldLoaded(seed, false);
        return world != null && world.getEnvironment() == World.Environment.THE_END;
    }

    private World ensureWorldLoaded(long seed, boolean allowCreate) {
        EndEventConfig cfg = config;
        Path path = managedWorldPath(cfg);
        if (!allowCreate && !Files.isDirectory(path)) return null;
        WorldCreator creator = new WorldCreator(cfg.endWorld())
                .environment(World.Environment.THE_END)
                .generateStructures(true)
                .seed(seed);
        return Bukkit.createWorld(creator);
    }

    public boolean evictManagedEndPlayers() {
        EndEventConfig cfg = config;
        World end = Bukkit.getWorld(cfg.endWorld());
        if (end == null) return true;
        World returnWorld = Bukkit.getWorld(cfg.returnWorld());
        if (returnWorld == null || returnWorld.getEnvironment() != World.Environment.NORMAL) return false;
        return evictPlayers(end, returnWorld);
    }

    public boolean evictPlayer(Player player) {
        EndEventConfig cfg = config;
        World returnWorld = Bukkit.getWorld(cfg.returnWorld());
        if (returnWorld == null || returnWorld.getEnvironment() != World.Environment.NORMAL) return false;
        return safeTeleport(player, returnWorld.getSpawnLocation());
    }

    public boolean unloadManagedEndAfterClose() {
        EndEventConfig cfg = config;
        if (!cfg.unloadAfterClose()) return true;
        World end = Bukkit.getWorld(cfg.endWorld());
        if (end == null) return true;
        if (!end.getPlayers().isEmpty()) return false;
        return Bukkit.unloadWorld(end, true);
    }

    public boolean isManagedEnd(World world) {
        return world != null && world.getName().equals(config.endWorld()) && world.getEnvironment() == World.Environment.THE_END;
    }

    public int playersInManagedEnd() {
        World end = Bukkit.getWorld(config.endWorld());
        return end == null ? 0 : end.getPlayers().size();
    }

    public boolean isManagedEndLoaded() {
        World end = Bukkit.getWorld(config.endWorld());
        return end != null && end.getEnvironment() == World.Environment.THE_END;
    }

    public Location returnLocation() {
        World world = Bukkit.getWorld(config.returnWorld());
        return world == null ? null : world.getSpawnLocation();
    }

    private boolean evictPlayers(World end, World returnWorld) {
        boolean success = true;
        Location spawn = returnWorld.getSpawnLocation();
        for (Player player : java.util.List.copyOf(end.getPlayers())) {
            if (!safeTeleport(player, spawn)) success = false;
        }
        return success && end.getPlayers().isEmpty();
    }

    private static boolean safeTeleport(Player player, Location destination) {
        try {
            return player.teleport(destination);
        } catch (Exception ex) {
            return false;
        }
    }

    private String validateWorldPath(EndEventConfig cfg) {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path world = managedWorldPath(cfg);
        Path returnPath = container.resolve(cfg.returnWorld()).normalize();
        if (world.equals(container)) return "Sciezka Endu wskazuje na world container";
        if (!world.startsWith(container)) return "Sciezka Endu wychodzi poza world container";
        if (!container.equals(world.getParent())) return "End musi byc bezposrednim katalogiem world container";
        if (world.equals(returnPath)) return "Sciezka Endu jest taka sama jak return-world";
        if (!world.getFileName().toString().equals(cfg.endWorld())) return "Nazwa katalogu Endu nie zgadza sie z configiem";
        return null;
    }

    private static Path managedWorldPath(EndEventConfig cfg) {
        return Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().resolve(cfg.endWorld()).normalize();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

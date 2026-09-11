package hex.minigames.score;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

public final class ScoreStorageFactory {
    private ScoreStorageFactory() {
    }

    public static MinigamesScoreRepository create(Plugin plugin, String storageType, String sqliteFile) {
        String type = storageType == null ? "auto" : storageType.toLowerCase();
        if (!type.equals("sqlite")) {
            try {
                RegisteredServiceProvider<HexApi> registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
                if (registration != null) {
                    MinigamesScoreRepository repository = new HexCoreMinigamesScoreRepository(registration.getProvider().db().db());
                    repository.initialize();
                    plugin.getLogger().info("HexMinigames scores use HexCore DB.");
                    return repository;
                }
            } catch (Throwable error) {
                if (type.equals("hexcore")) {
                    plugin.getLogger().warning("HexCore score storage requested but unavailable: " + rootMessage(error));
                } else {
                    plugin.getLogger().warning("HexCore score storage unavailable, falling back to SQLite: " + rootMessage(error));
                }
            }
        }
        MinigamesScoreRepository repository = new SQLiteMinigamesScoreRepository(plugin, sqliteFile);
        repository.initialize();
        return repository;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

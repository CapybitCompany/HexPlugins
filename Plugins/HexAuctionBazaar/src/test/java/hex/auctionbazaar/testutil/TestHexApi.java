package hex.auctionbazaar.testutil;

import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.core.api.HexApi;
import hex.core.api.db.DatabaseService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Minimalny {@link HexApi} do testów: udostępnia wyłącznie {@link #db()} (pozostałe usługi nie są
 * potrzebne w testach usług Aukcji/Rynku i zwracają {@code null}). Pozwala zbootstrapować
 * {@link HexCoreBridge} przez Bukkit ServicesManager, aby testować PEŁNE ścieżki async z prawdziwymi
 * usługami zamiast wyłącznie czystych funkcji decyzyjnych.
 */
public final class TestHexApi implements HexApi {

    private final DatabaseService db;

    public TestHexApi(DatabaseService db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    /** Rejestruje ten HexApi w ServicesManager MockBukkit i zwraca zbootstrapowany {@link HexCoreBridge}. */
    public static HexCoreBridge bootstrap(Plugin plugin, DatabaseService db) {
        TestHexApi api = new TestHexApi(db);
        Bukkit.getServicesManager().register(HexApi.class, api, plugin, ServicePriority.Normal);
        HexCoreBridge bridge = new HexCoreBridge(Logger.getAnonymousLogger());
        if (!bridge.tryBootstrap()) {
            throw new IllegalStateException("Nie udało się zbootstrapować testowego HexCoreBridge");
        }
        return bridge;
    }

    @Override
    public DatabaseService db() {
        return db;
    }

    @Override
    public hex.core.api.config.ConfigService configs() {
        return null;
    }

    @Override
    public hex.core.api.flags.FeatureFlagService flags() {
        return null;
    }

    @Override
    public hex.core.api.ui.UiService ui() {
        return null;
    }

    @Override
    public hex.core.api.region.RegionService regions() {
        return null;
    }

    @Override
    public hex.core.service.ranking.RankingPointsService rankingPoints() {
        return null;
    }

    @Override
    public hex.core.service.coins.CoinsService coins() {
        return null;
    }

    @Override
    public hex.core.service.cache.PlayerStatsCacheService statsCache() {
        return null;
    }
}

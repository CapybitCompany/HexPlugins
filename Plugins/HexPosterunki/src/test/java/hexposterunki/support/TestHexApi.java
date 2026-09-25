package hexposterunki.support;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;
import hex.core.service.cache.PlayerStatsCacheService;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Minimal {@link HexApi} for integration tests: the real UI service plus a database service backed
 * by a real JDBC connection.
 *
 * <p>Database work runs on a single background thread, exactly like production, so the tests
 * exercise the real "main thread produces, database thread writes" split rather than pretending
 * everything is synchronous.
 */
public final class TestHexApi implements HexApi {

    private final UiService ui;
    private final DatabaseService database;

    public TestHexApi(UiService ui, Db db) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.database = new SingleThreadDatabaseService(Objects.requireNonNull(db, "db"));
    }

    @Override
    public ConfigService configs() {
        return null;
    }

    @Override
    public FeatureFlagService flags() {
        return null;
    }

    @Override
    public UiService ui() {
        return ui;
    }

    @Override
    public RegionService regions() {
        return null;
    }

    @Override
    public DatabaseService db() {
        return database;
    }

    @Override
    public RankingPointsService rankingPoints() {
        return null;
    }

    @Override
    public CoinsService coins() {
        return null;
    }

    @Override
    public PlayerStatsCacheService statsCache() {
        return null;
    }

    public void shutdown() {
        database.shutdown();
    }

    /**
     * Blocks the single database thread until the returned handle is run, so a test can hold a
     * database callback back deliberately. Work submitted meanwhile queues up in order.
     */
    public Runnable holdDatabase() {
        return ((SingleThreadDatabaseService) database).hold();
    }

    /** Lets queued work finish, then stops the database thread - like an orderly process exit. */
    public void shutdownGracefully() throws InterruptedException {
        ((SingleThreadDatabaseService) database).shutdownGracefully();
    }

    /** Mirrors HexCore's contract: {@code async} hands work to a database thread. */
    private static final class SingleThreadDatabaseService implements DatabaseService {

        private final Db db;
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "Test-DB");
                    thread.setDaemon(true);
                    return thread;
                });

        private SingleThreadDatabaseService(Db db) {
            this.db = db;
        }

        @Override
        public Db db() {
            return db;
        }

        @Override
        public <T> CompletableFuture<T> async(Supplier<T> work) {
            return CompletableFuture.supplyAsync(work, executor);
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }

        private Runnable hold() {
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return release::countDown;
        }

        private void shutdownGracefully() throws InterruptedException {
            executor.shutdown();
            executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}

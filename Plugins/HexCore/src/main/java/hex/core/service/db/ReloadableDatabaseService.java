package hex.core.service.db;

import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stable DatabaseService facade used by HexApi.
 *
 * Plugins often keep references to api.db() or even api.db().db() during startup.
 * This wrapper keeps those references valid while allowing HexCore to swap the
 * underlying database pool during /hexcore reload db.
 */
public final class ReloadableDatabaseService implements DatabaseService {
    private final Plugin plugin;
    private final AtomicReference<DatabaseService> delegate;
    private final Set<DatabaseService> retiredDelegates = ConcurrentHashMap.newKeySet();
    private final Db dbProxy = new DelegatingDb();

    public ReloadableDatabaseService(Plugin plugin, DatabaseService initialDelegate) {
        this.plugin = plugin;
        this.delegate = new AtomicReference<>(initialDelegate);
    }

    public DatabaseService current() {
        return delegate.get();
    }

    public boolean isActiveHikari() {
        return current() instanceof HikariDatabaseService;
    }

    public void swap(DatabaseService newDelegate) {
        DatabaseService old = delegate.getAndSet(newDelegate);
        if (old == null || old == newDelegate) {
            return;
        }

        // Give in-flight synchronous DB operations a short grace period before
        // closing the old pool. New async work is routed to the new delegate immediately.
        retiredDelegates.add(old);
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (retiredDelegates.remove(old)) {
                old.shutdown();
            }
        }, 100L);
    }

    @Override
    public Db db() {
        return dbProxy;
    }

    @Override
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        return current().async(work);
    }

    @Override
    public void shutdown() {
        DatabaseService current = delegate.getAndSet(new NoopDatabaseService("HexCore database is shut down"));
        if (current != null) {
            current.shutdown();
        }
        for (DatabaseService retired : retiredDelegates) {
            retired.shutdown();
        }
        retiredDelegates.clear();
    }

    private final class DelegatingDb implements Db {
        private Db currentDb() {
            return ReloadableDatabaseService.this.current().db();
        }

        @Override
        public int update(String sql, Object... params) {
            return currentDb().update(sql, params);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            return currentDb().query(sql, mapper, params);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return currentDb().queryOne(sql, mapper, params);
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            return currentDb().batch(sql, batchParams);
        }

        @Override
        public <T> T tx(Function<Db, T> work) {
            return currentDb().tx(work);
        }

        @Override
        public String tablePrefix() {
            return currentDb().tablePrefix();
        }
    }
}



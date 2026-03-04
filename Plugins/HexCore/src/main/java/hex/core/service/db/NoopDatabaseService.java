package hex.core.service.db;

import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class NoopDatabaseService implements DatabaseService {

    private final RuntimeException error;

    public NoopDatabaseService(String reason) {
        this.error = new IllegalStateException(reason);
    }

    @Override
    public Db db() {
        throw error;
    }

    @Override
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(error);
        return f;
    }

    @Override
    public void shutdown() { }
}
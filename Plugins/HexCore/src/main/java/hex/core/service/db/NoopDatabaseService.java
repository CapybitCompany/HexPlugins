package hex.core.service.db;

import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class NoopDatabaseService implements DatabaseService {

    private final String reason;
    private final RuntimeException error;

    public NoopDatabaseService(String reason) {
        this.reason = reason == null || reason.isBlank() ? "Database is unavailable" : reason;
        this.error = new IllegalStateException(this.reason);
    }

    @Override
    public Db db() {
        throw error;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return reason;
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
package hex.core.api.db;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public interface DatabaseService {

    Db db();

    default boolean isAvailable() {
        return true;
    }

    default String unavailableReason() {
        return null;
    }

    <T> CompletableFuture<T> async(Supplier<T> work);

    default CompletableFuture<Void> asyncRun(Runnable work) {
        return async(() -> { work.run(); return null; });
    }

    void shutdown();
}

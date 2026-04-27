package hex.core.api.db;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface DatabaseService {

    Db db();

    <T> CompletableFuture<T> async(Supplier<T> work);

    default CompletableFuture<Void> asyncRun(Runnable work) {
        return async(() -> { work.run(); return null; });
    }

    void shutdown();
}

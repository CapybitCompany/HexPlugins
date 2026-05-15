package hexabovename.service;

import hexabovename.repository.DisplayTextRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class TitleMutationService {

    private final DisplayTextRepository repository;
    private final ExecutorService executor;

    public TitleMutationService(DisplayTextRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    public CompletableFuture<Void> setTitle(UUID uuid, String playerName, String text) {
        return CompletableFuture.runAsync(() -> {
            try {
                repository.upsertDisplayText(uuid, playerName, text);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> clearTitle(UUID uuid, String playerName) {
        return CompletableFuture.runAsync(() -> {
            try {
                repository.clearDisplayText(uuid, playerName);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, executor);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HexAboveName-Write");
            thread.setDaemon(true);
            return thread;
        }
    }
}

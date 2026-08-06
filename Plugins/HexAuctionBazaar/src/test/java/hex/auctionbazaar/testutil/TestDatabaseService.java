package hex.auctionbazaar.testutil;

import hex.core.api.db.Db;
import hex.core.api.db.DatabaseService;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Testowy {@link DatabaseService} opakowujący dowolny {@link Db} (np. {@link InMemoryDb}).
 *
 * <p>Domyślnie async wykonuje pracę SYNCHRONICZNIE na wątku wołającego i zwraca domknięty
 * future - dzięki temu testy pełnych ścieżek usług są deterministyczne. Tryb
 * {@link Mode#THROW_ON_ASYNC} pozwala zasymulować synchroniczny błąd zgłoszenia async
 * (np. odrzucenie przez executor), by udowodnić, że wołający tego nie dostaje.</p>
 *
 * <p>{@link #shutdown()} jest świadomym no-opem: testy sprawdzają, że plugin NIGDY nie
 * zamyka puli/executora HexCore.</p>
 */
public final class TestDatabaseService implements DatabaseService {

    public enum Mode {
        /** Praca wykonywana synchronicznie; future domknięty od razu. */
        DIRECT,
        /** {@code async()} rzuca synchronicznie (symuluje odrzucenie przez executor). */
        THROW_ON_ASYNC,
        /** {@code async()} zwraca future, który NIGDY się nie domyka (praca „w locie" - do testu busy-guard). */
        PENDING_ASYNC,
        /** {@code async()} zwraca {@code null} (symuluje zdegenerowany kontrakt - do testu totalności). */
        NULL_ASYNC
    }

    private final Db db;
    private volatile Mode mode;
    private volatile boolean shutdownCalled = false;

    public TestDatabaseService(Db db) {
        this(db, Mode.DIRECT);
    }

    public TestDatabaseService(Db db, Mode mode) {
        this.db = Objects.requireNonNull(db, "db");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public boolean shutdownCalled() {
        return shutdownCalled;
    }

    @Override
    public Db db() {
        return db;
    }

    @Override
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        if (mode == Mode.THROW_ON_ASYNC) {
            throw new IllegalStateException("async submit rejected (test)");
        }
        if (mode == Mode.PENDING_ASYNC) {
            return new CompletableFuture<>();   // nigdy się nie domyka - praca „w locie"
        }
        if (mode == Mode.NULL_ASYNC) {
            return null;   // zdegenerowany kontrakt: wołający musi to znieść terminalnie
        }
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public void shutdown() {
        // Świadomy no-op: HexCore-pool/executor nie może być zamykany przez plugin.
        shutdownCalled = true;
    }
}

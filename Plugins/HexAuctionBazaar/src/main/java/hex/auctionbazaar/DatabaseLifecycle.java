package hex.auctionbazaar;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maszyna stanów inicjalizacji/recovery bazy danych (odseparowana od Bukkit,
 * więc w pełni testowalna). Efekty (healthcheck, schema, start/stop tasków,
 * disable, logi, scheduler) są wstrzykiwane przez {@link Effects}.
 *
 * Gwarancje:
 *  - żaden potencjalnie rzucający dostęp do DB nie następuje poza chronioną ścieżką;
 *  - {@code required=true} + błąd healthchecku/schematu -> disable pluginu;
 *  - {@code required=false} + błąd -> plugin zostaje, {@code dbHealthy=false},
 *    {@code schemaReady=false} (transakcje zablokowane), reload/dbstatus działają;
 *  - {@code dbHealthy=true} DOPIERO gdy schemat jest gotowy (spójny stan);
 *  - inicjalizacje są SERIALIZOWANE: w danej chwili aktywny jest co najwyżej jeden
 *    {@code initSchema()}/seed. Reload w trakcie NIE uruchamia drugiego DDL/seed
 *    równolegle - dopiero po zakończeniu bieżącej pracy DB startuje NAJNOWSZA
 *    generacja (koalescencja pośrednich reloadów);
 *  - żaden Bukkit-task nie jest planowany dla dezaktywowanego pluginu ani dla
 *    nieaktualnej generacji (sprawdzenie PRZED planowaniem i w callbacku).
 */
public final class DatabaseLifecycle {

    /** Wstrzykiwane efekty. Wszystkie {@code CompletableFuture} zwracane bez rzucania synchronicznie. */
    public interface Effects {
        /** Asynchroniczny SELECT 1 -> true gdy OK. Musi sam łapać wyjątki (zwraca false). */
        CompletableFuture<Boolean> healthCheck();

        /** Asynchroniczna inicjalizacja schematu (ensureTable + seed). Future kończy się wyjątkiem przy błędzie. */
        CompletableFuture<Void> initSchema();

        /** Planuje zadanie na wątku głównym; MUSI być kontrolowanym no-op gdy plugin wyłączony. */
        void runMain(Runnable task);

        void startTasks();

        void stopTasks();

        void disablePlugin();

        void logInfo(String message);

        void logWarn(String message);

        void logSevere(String message, Throwable error);
    }

    private final Effects fx;
    private final AtomicBoolean dbHealthy = new AtomicBoolean(true);
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    // Stan chroniony przez 'this' (write); czytany volatile w callbackach async.
    private volatile int generation = 0;      // najnowsza żądana generacja
    private volatile boolean active = true;   // false po invalidate()/disable
    private int runningToken = -1;            // aktualnie wykonywana generacja, -1 = brak
    private boolean requiredLatest;
    private boolean healthCheckLatest;

    public DatabaseLifecycle(Effects fx) {
        this.fx = Objects.requireNonNull(fx, "fx");
    }

    public boolean dbHealthy() {
        return dbHealthy.get();
    }

    public boolean schemaReady() {
        return schemaReady.get();
    }

    public int generation() {
        return generation;
    }

    /** Start lub restart (reload) inicjalizacji z aktualnymi wartościami konfiguracji. */
    public synchronized void start(boolean required, boolean healthCheckOnStartup) {
        active = true;
        generation++;
        requiredLatest = required;
        healthCheckLatest = healthCheckOnStartup;
        schemaReady.set(false);
        fx.stopTasks();                       // zatrzymaj stare taski NATYCHMIAST przy reloadzie
        if (runningToken == -1) {
            beginRun();
        }
        // W przeciwnym razie bieżący run dokończy pracę DB i sam uruchomi najnowszą generację.
    }

    private synchronized void beginRun() {
        if (!active) {
            runningToken = -1;
            return;
        }
        int token = generation;
        boolean required = requiredLatest;
        boolean hc = healthCheckLatest;
        runningToken = token;

        CompletableFuture<Boolean> health;
        if (hc) {
            try {
                CompletableFuture<Boolean> f = fx.healthCheck();
                health = f == null ? CompletableFuture.completedFuture(false) : f.exceptionally(ex -> false);
            } catch (Throwable t) {
                health = CompletableFuture.completedFuture(false);
            }
        } else {
            health = CompletableFuture.completedFuture(true);
        }
        health.whenComplete((ok, err) -> afterHealth(token, required, hc, err == null && Boolean.TRUE.equals(ok)));
    }

    /**
     * Po healthchecku (synchronized). PRZED logiem i {@code initSchema()} sprawdzamy pod lockiem:
     * plugin aktywny, to nasz bieżący run i najnowsza generacja. Dzięki temu:
     *  - po invalidate()/disable NIE logujemy healthchecku i NIE startujemy schematu (schemat jeszcze
     *    nie ruszył), zwalniamy tylko runningToken;
     *  - gdy w trakcie oczekiwania na healthcheck pojawił się reload (nowsza generacja), NIE logujemy
     *    i NIE uruchamiamy initSchema dla starej generacji; kończymy stary run czysto i startujemy
     *    wyłącznie najnowszą generację (bez zbędnej starej inicjalizacji schematu).
     * (Reload w trakcie już trwającego initSchema jest obsłużony w completeRun - stara praca DB kończy
     * się do końca, wynik nie jest stosowany, a potem startuje najnowsza generacja.)
     */
    private synchronized void afterHealth(int token, boolean required, boolean hc, boolean ok) {
        if (token != runningToken) {
            return;                                   // nie nasz run - nic nie robimy
        }
        if (!active) {
            runningToken = -1;                        // wyłączony podczas oczekiwania - zwolnij slot
            return;                                   // brak logu/schematu/statusu/tasków
        }
        if (token != generation) {
            // Reload podczas oczekiwania na healthcheck: schemat jeszcze NIE ruszył.
            runningToken = -1;
            beginRun();                               // startuje TYLKO najnowszą generację
            return;
        }
        // Najnowszy, aktywny run -> log + (initSchema albo fail).
        fx.logInfo(hc
                ? "HexAuctionBazaar: healthcheck bazy: " + (ok ? "OK" : "NIEPOWODZENIE") + "."
                : "HexAuctionBazaar: healthcheck bazy: POMINIĘTY (health-check-on-startup=false).");
        if (!ok) {
            completeRun(token, required, false, null);   // reentrant (ten sam wątek/monitor)
            return;
        }
        CompletableFuture<Void> init;
        try {
            CompletableFuture<Void> f = fx.initSchema();
            init = f == null ? CompletableFuture.completedFuture(null) : f;
        } catch (Throwable t) {
            init = CompletableFuture.failedFuture(t);
        }
        init.whenComplete((v, err) -> completeRun(token, required, err == null, err));
    }

    /** Praca DB tej generacji zakończona. Zastosuj wynik gdy najnowsza; inaczej uruchom najnowszą. */
    private synchronized void completeRun(int token, boolean required, boolean success, Throwable schemaErr) {
        runningToken = -1;
        boolean latest = (token == generation);
        if (!latest) {
            // Nowsze reloady w kolejce -> po zakończeniu pracy DB startuje TYLKO najnowsza generacja.
            if (active) {
                beginRun();
            }
            return;   // spóźniony wynik nie ustawia stanu ani nie startuje tasków
        }
        if (!active) {
            return;
        }
        if (success) {
            runMainGuarded(token, () -> applySuccess(token));
        } else {
            if (schemaErr != null) {
                // Nieoczekiwany błąd schematu -> log techniczny (bez danych dostępowych).
                fx.logSevere("HexAuctionBazaar: inicjalizacja schematu bazy nie powiodła się.", schemaErr);
            }
            runMainGuarded(token, () -> applyFail(token, required));
        }
    }

    private void applySuccess(int token) {
        if (!active || token != generation) {
            return;
        }
        dbHealthy.set(true);
        schemaReady.set(true);
        fx.logInfo("HexAuctionBazaar: schemat bazy gotowy.");
        fx.startTasks();
    }

    private void applyFail(int token, boolean required) {
        if (!active || token != generation) {
            return;
        }
        dbHealthy.set(false);
        schemaReady.set(false);
        if (required) {
            fx.logSevere("HexAuctionBazaar: baza wymagana (database.required=true) i niedostępna "
                    + "- wyłączam plugin.", null);
            fx.disablePlugin();
        } else {
            fx.logWarn("HexAuctionBazaar: baza niedostępna - transakcje Aukcji/Rynku wyłączone "
                    + "(database.required=false).");
        }
    }

    /** Planuj na wątku głównym TYLKO gdy plugin aktywny i generacja aktualna (punkt #5). */
    private void runMainGuarded(int token, Runnable r) {
        if (!active || token != generation) {
            return;                                   // pre-check PRZED planowaniem
        }
        fx.runMain(() -> {
            if (!active || token != generation) {
                return;                               // ponowne sprawdzenie w callbacku (wyścig)
            }
            r.run();
        });
    }

    /** onDisable / recovery: unieważnij wszystkie generacje (spóźnione wyniki nic nie zrobią). */
    public synchronized void invalidate() {
        active = false;
        generation++;
        schemaReady.set(false);
    }
}

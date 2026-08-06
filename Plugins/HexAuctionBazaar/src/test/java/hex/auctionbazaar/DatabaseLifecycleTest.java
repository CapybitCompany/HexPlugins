package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkty #1/#3/#4: maszyna stanów inicjalizacji/recovery bazy.
 * Fake Effects z ręcznie kontrolowanymi Future'ami - sterujemy healthchekiem i
 * schematem, sprawdzamy required-kombinacje, generacje i brak duplikatów tasków.
 */
class DatabaseLifecycleTest {

    private static final class FakeEffects implements DatabaseLifecycle.Effects {
        final List<CompletableFuture<Boolean>> healthCalls = new ArrayList<>();
        final List<CompletableFuture<Void>> schemaCalls = new ArrayList<>();
        final List<String> infoLogs = new ArrayList<>();
        boolean healthThrows = false;
        int startTasks = 0;
        int stopTasks = 0;
        int disable = 0;
        boolean pluginActive = true;
        boolean runMainCalledWhileInactive = false;

        @Override
        public CompletableFuture<Boolean> healthCheck() {
            if (healthThrows) throw new RuntimeException("noop db threw");
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            healthCalls.add(f);
            return f;
        }

        @Override
        public CompletableFuture<Void> initSchema() {
            CompletableFuture<Void> f = new CompletableFuture<>();
            schemaCalls.add(f);
            return f;
        }

        @Override public void runMain(Runnable task) {
            if (!pluginActive) runMainCalledWhileInactive = true;
            task.run();
        }
        @Override public void startTasks() { startTasks++; }
        @Override public void stopTasks() { stopTasks++; }
        @Override public void disablePlugin() { disable++; }
        @Override public void logInfo(String message) { infoLogs.add(message); }
        @Override public void logWarn(String message) { }
        @Override public void logSevere(String message, Throwable error) { }

        CompletableFuture<Boolean> lastHealth() { return healthCalls.get(healthCalls.size() - 1); }
        CompletableFuture<Void> lastSchema() { return schemaCalls.get(schemaCalls.size() - 1); }
    }

    // ---- healthcheck ----

    @Test
    void healthcheckFailRequiredTrueDisables() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);
        fx.lastHealth().complete(false);
        assertEquals(1, fx.disable);
        assertFalse(lc.dbHealthy());
        assertFalse(lc.schemaReady());
        assertEquals(0, fx.startTasks);
    }

    @Test
    void healthcheckFailRequiredFalseStaysEnabled() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(false, true);
        fx.lastHealth().complete(false);
        assertEquals(0, fx.disable, "required=false nie wyłącza pluginu");
        assertFalse(lc.dbHealthy());
        assertFalse(lc.schemaReady());
    }

    @Test
    void throwingHealthCheckDoesNotPropagateAndTreatedUnhealthy() {
        FakeEffects fx = new FakeEffects();
        fx.healthThrows = true;
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        // Noop/rzucająca DB + required:false -> start nie rzuca, plugin zostaje.
        lc.start(false, true);
        assertFalse(lc.dbHealthy());
        assertFalse(lc.schemaReady());
        assertEquals(0, fx.disable);
    }

    // ---- schema ----

    @Test
    void schemaFailRequiredTrueDisables() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);
        fx.lastHealth().complete(true);
        fx.lastSchema().completeExceptionally(new RuntimeException("schema broken"));
        assertEquals(1, fx.disable);
        assertFalse(lc.schemaReady());
        assertFalse(lc.dbHealthy());
    }

    @Test
    void schemaFailRequiredFalseStaysEnabled() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(false, true);
        fx.lastHealth().complete(true);
        fx.lastSchema().completeExceptionally(new RuntimeException("schema broken"));
        assertEquals(0, fx.disable);
        assertFalse(lc.schemaReady());
        assertFalse(lc.dbHealthy());
    }

    @Test
    void successSetsHealthyAndReadyAndStartsTasksOnce() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);
        fx.lastHealth().complete(true);
        fx.lastSchema().complete(null);
        assertTrue(lc.dbHealthy());
        assertTrue(lc.schemaReady());
        assertEquals(1, fx.startTasks);
        assertEquals(0, fx.disable);
    }

    @Test
    void successfulSelectButSchemaFailDoesNotFreeTransactions() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(false, true);
        fx.lastHealth().complete(true);         // SELECT 1 OK
        // dbHealthy NIE ustawione na true tylko dlatego, że SELECT 1 przeszedł:
        assertFalse(lc.schemaReady());
        fx.lastSchema().completeExceptionally(new RuntimeException("schema broken"));
        assertFalse(lc.schemaReady(), "schemat błędny -> transakcje zablokowane");
        assertFalse(lc.dbHealthy());
        assertEquals(0, fx.startTasks);
    }

    // ---- health-check-on-startup:false ----

    @Test
    void skipHealthCheckStillProtectsSchemaAndRespectsRequiredTrue() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, false);                  // pomija SELECT 1
        assertTrue(fx.healthCalls.isEmpty(), "SELECT 1 pominięty");
        fx.lastSchema().completeExceptionally(new RuntimeException("schema broken"));
        assertEquals(1, fx.disable, "błąd schematu nadal respektuje required=true");
    }

    @Test
    void skipHealthCheckRespectsRequiredFalse() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(false, false);
        fx.lastSchema().completeExceptionally(new RuntimeException("schema broken"));
        assertEquals(0, fx.disable, "required=false nie wyłącza mimo błędu schematu");
        assertFalse(lc.schemaReady());
    }

    // ---- reload / generation ----

    @Test
    void reloadFromDownToUpInitializesSchemaAndTasks() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        // Pierwszy start: DB niedostępna, required=false.
        lc.start(false, true);
        fx.lastHealth().complete(false);
        assertFalse(lc.schemaReady());
        // Reload: DB znów dostępna, schemat OK.
        lc.start(false, true);
        fx.lastHealth().complete(true);
        fx.lastSchema().complete(null);
        assertTrue(lc.dbHealthy());
        assertTrue(lc.schemaReady());
        assertEquals(1, fx.startTasks);
    }

    @Test
    void multipleReloadsDoNotDuplicateTasks() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        for (int i = 0; i < 3; i++) {
            lc.start(true, true);
            fx.lastHealth().complete(true);
            fx.lastSchema().complete(null);
        }
        assertEquals(3, fx.startTasks);
        // Każdy start zatrzymuje stare taski przed nowymi -> brak nakładania.
        assertTrue(fx.stopTasks >= 3, "stopTasks wołane przed każdą inicjalizacją");
    }

    // ---- #4 serializacja: brak równoległych initSchema/seed, koalescencja reloadów ----

    @Test
    void reloadDuringSchemaDoesNotStartSecondSchemaInParallel() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);                          // gen1
        fx.lastHealth().complete(true);                // gen1 health OK -> gen1 initSchema startuje
        assertEquals(1, fx.schemaCalls.size(), "gen1 initSchema uruchomiony");
        lc.start(true, true);                          // gen2 (w trakcie gen1)
        assertEquals(1, fx.schemaCalls.size(), "brak DRUGIEGO initSchema równolegle podczas gen1");
        assertEquals(0, fx.startTasks);
    }

    @Test
    void multipleReloadsDuringSchemaCoalesceToNewestOnly() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);                          // gen1
        fx.lastHealth().complete(true);                // gen1 initSchema startuje
        lc.start(true, true);                          // gen2 (pominięty)
        lc.start(true, true);                          // gen3 (najnowszy)
        assertEquals(1, fx.schemaCalls.size(), "wciąż tylko gen1 initSchema");

        fx.lastSchema().complete(null);                // gen1 DB skończone -> startuje NAJNOWSZA (gen3)
        assertEquals(0, fx.startTasks, "wynik gen1 nie startuje tasków (nie jest najnowszy)");
        assertEquals(2, fx.healthCalls.size(), "po gen1 startuje gen3 healthcheck (gen2 pominięty)");
        assertEquals(1, fx.schemaCalls.size(), "gen3 initSchema czeka na healthcheck");

        fx.lastHealth().complete(true);                // gen3 health -> gen3 initSchema
        assertEquals(2, fx.schemaCalls.size(), "dokładnie jeden dodatkowy initSchema (gen3)");
        fx.lastSchema().complete(null);                // gen3 schema -> gotowe
        assertTrue(lc.schemaReady());
        assertEquals(1, fx.startTasks, "najnowsza generacja startuje taski dokładnie raz");
    }

    @Test
    void newRunBeginsOnlyAfterOldDbWorkFinished() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);                          // gen1
        fx.lastHealth().complete(true);                // gen1 initSchema (pending)
        lc.start(true, true);                          // gen2 - czeka
        // Dopóki gen1 initSchema NIE skończy, gen2 nie startuje żadnej pracy DB.
        assertEquals(1, fx.schemaCalls.size());
        assertEquals(1, fx.healthCalls.size());
        fx.lastSchema().complete(null);                // gen1 DB skończone -> gen2 zaczyna
        assertEquals(2, fx.healthCalls.size(), "gen2 startuje dopiero po zakończeniu pracy DB gen1");
    }

    // ---- #5 brak planowania na wątku głównym po disable ----

    @Test
    void invalidateAfterDisableStartsNoTasks() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);
        fx.lastHealth().complete(true);         // gen1 initSchema pending
        lc.invalidate();                        // onDisable
        fx.lastSchema().complete(null);         // spóźniony wynik po disable
        assertEquals(0, fx.startTasks);
        assertEquals(0, fx.disable);
        assertFalse(lc.schemaReady());
    }

    @Test
    void runMainNotInvokedAfterDisable() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);
        fx.lastHealth().complete(true);         // gen1 initSchema pending
        fx.pluginActive = false;                // plugin "wyłączony"
        lc.invalidate();
        // Spóźnione zakończenie schematu po disable nie planuje niczego na wątku głównym.
        fx.lastSchema().complete(null);
        assertEquals(0, fx.startTasks);
        assertFalse(fx.runMainCalledWhileInactive, "runMain nie wywołane dla wyłączonego pluginu");
    }

    // ---- #1 disable/reload PODCZAS oczekiwania na healthcheck (schemat jeszcze nie ruszył) ----

    @Test
    void disableDuringPendingHealthcheckStartsNoSchemaNorTasksNorLogs() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);                          // gen1: healthcheck PENDING
        assertEquals(1, fx.healthCalls.size());
        assertEquals(0, fx.schemaCalls.size(), "schemat jeszcze nie ruszył (health pending)");

        lc.invalidate();                               // onDisable PODCZAS oczekiwania na healthcheck
        fx.lastHealth().complete(true);                // spóźniony, POZYTYWNY healthcheck

        assertEquals(0, fx.schemaCalls.size(), "po disable NIE uruchamiamy initSchema");
        assertEquals(0, fx.startTasks, "brak startu tasków");
        assertEquals(0, fx.disable, "healthcheck OK niczego nie wyłącza");
        assertFalse(lc.schemaReady(), "brak zmiany statusu na gotowy");
        assertFalse(fx.runMainCalledWhileInactive, "brak planowania na wątku głównym po disable");
        assertTrue(fx.infoLogs.isEmpty(),
                "brak logu healthchecku i brak fałszywego logu sukcesu po disable");
    }

    @Test
    void reloadDuringPendingHealthcheckRunsOnlyNewestGenerationNoStaleLogsNoParallel() {
        FakeEffects fx = new FakeEffects();
        DatabaseLifecycle lc = new DatabaseLifecycle(fx);
        lc.start(true, true);                          // gen1: healthcheck PENDING
        CompletableFuture<Boolean> gen1Health = fx.lastHealth();
        assertEquals(1, fx.healthCalls.size());

        lc.start(true, true);                          // gen2: reload PODCZAS oczekiwania na gen1 health
        assertEquals(1, fx.healthCalls.size(), "gen2 czeka - brak drugiego healthchecku równolegle");
        assertEquals(0, fx.schemaCalls.size(), "żaden initSchema nie ruszył (gen1 health wciąż pending)");

        gen1Health.complete(true);                     // spóźniony, POZYTYWNY gen1 health
        assertEquals(0, fx.schemaCalls.size(), "gen1 NIE startuje schematu (przestarzała generacja)");
        assertEquals(2, fx.healthCalls.size(), "startuje wyłącznie NAJNOWSZY gen2 healthcheck");
        assertTrue(fx.infoLogs.isEmpty(), "brak mylących logów gen1 (healthcheck/sukces)");

        fx.lastHealth().complete(true);                // gen2 health OK -> gen2 initSchema
        assertEquals(1, fx.schemaCalls.size(), "tylko gen2 uruchamia initSchema (brak równoległości)");
        fx.lastSchema().complete(null);
        assertTrue(lc.schemaReady());
        assertEquals(1, fx.startTasks, "tylko najnowsza generacja startuje taski dokładnie raz");
    }
}

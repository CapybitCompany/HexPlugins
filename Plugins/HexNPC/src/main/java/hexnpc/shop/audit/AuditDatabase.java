package hexnpc.shop.audit;

import java.util.concurrent.CompletableFuture;

/**
 * Minimalny kontrakt bazy potrzebny do audytu — pozwala odizolować refleksję
 * HexCore ({@link HexCoreDatabase}) i podstawić atrapę w testach. Wszystkie
 * zapisy DDL/INSERT muszą iść przez {@link #submit(Runnable)} (poza wątkiem
 * głównym); {@link #execUpdate} wolno wołać tylko wewnątrz takiej pracy.
 */
public interface AuditDatabase {

    /** Pełna nazwa tabeli z prefiksem (np. {@code Db.t(name)}). */
    String table(String name);

    /** Uruchamia pracę DB poza wątkiem głównym i zwraca future zakończenia. */
    CompletableFuture<Void> submit(Runnable work);

    /** Parametryzowany UPDATE/DDL — tylko wewnątrz {@link #submit(Runnable)}. */
    int execUpdate(String sql, Object... params) throws Exception;
}

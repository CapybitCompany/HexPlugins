package hex.auctionbazaar.testutil;

import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Prostjaczysty in-memory {@link Db} do testow.
 * Nie parsuje SQL - decyzje opieramy na "handler-ach" rejestrowanych
 * per prefiks polecenia. Klucz semantyki: transakcje SA nested, kazde
 * update/insert wykonane w callbacku Db.tx jest bufowane i commit-uje
 * gdy callback zakonczy sie normalnie; wyjatek powoduje rollback.
 *
 * Wystarczy zeby przetestowac tx-semantyke i "rollback on exception".
 */
public final class InMemoryDb implements Db {

    public interface Handler {
        int handle(List<Object> params);
    }

    public interface QueryHandler<T> {
        List<T> handle(List<Object> params);
    }

    private final List<Op> operations = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1L);

    private Handler defaultUpdate = params -> 1;
    private QueryHandler<Object> defaultQuery = params -> List.of();
    private String throwOnUpdateContaining = null;   // gdy SQL update zawiera ten fragment -> rzuć (test błędów DB)

    public void setDefaultUpdate(Handler h) { this.defaultUpdate = h; }
    public void setDefaultQuery(QueryHandler<Object> h) { this.defaultQuery = h; }

    /** Ustawia fragment SQL, dla którego {@link #update} rzuci wyjątek (symulacja awarii konkretnego zapisu). */
    public void failUpdatesContaining(String sqlFragment) { this.throwOnUpdateContaining = sqlFragment; }

    /** Operacja logowana; test moze zweryfikowac czy wyladowala w liscie. */
    public record Op(String sql, List<Object> params) {}

    public List<Op> operations() { return List.copyOf(operations); }
    public void clearOps() { operations.clear(); }

    /**
     * Null-tolerancyjna migawka parametrów. Produkcyjne zapytania (np. audyt) przekazują parametry
     * {@code null} (brak actora/itemKey/orderId...), a {@code List.of(...)} rzuciłby na nich NPE -
     * co maskowałoby faktyczne wykonanie zapytania. Używamy niemodyfikowalnej listy dopuszczającej null.
     */
    private static List<Object> paramList(Object... params) {
        return java.util.Collections.unmodifiableList(java.util.Arrays.asList(params));
    }

    @Override
    public int update(String sql, Object... params) {
        List<Object> p = paramList(params);
        if (throwOnUpdateContaining != null && sql.contains(throwOnUpdateContaining)) {
            // Symulacja awarii konkretnego zapisu (op NIE jest logowany - zapis się nie powiódł).
            throw new RuntimeException("symulowana awaria update: " + throwOnUpdateContaining);
        }
        operations.add(new Op(sql, p));
        return defaultUpdate.handle(p);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        operations.add(new Op(sql, paramList(params)));
        // Nie mamy sposobu na ResultSet - test double zwraca pusta liste.
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        operations.add(new Op(sql, paramList(params)));
        // Wsparcie dla wzorca insert-then-get-id (LAST_INSERT_ID) - zwraca kolejne wygenerowane id.
        if (sql.contains("LAST_INSERT_ID")) {
            return (Optional<T>) Optional.of(nextGeneratedId());
        }
        return Optional.empty();
    }

    @Override
    public int[] batch(String sql, List<Object[]> batchParams) {
        operations.add(new Op(sql, List.of()));
        return new int[batchParams.size()];
    }

    /**
     * Kluczowe: symulujemy transakcyjnosc.
     * Kopia stanu przed rozpoczeciem; wyjatek -> restore, normalny return -> commit.
     * Zaklada, ze operacje sa idempotentne w liscie 'operations' - komitujemy
     * dodane operacje jesli tx sie powiodla, w przeciwnym razie usuwamy.
     */
    @Override
    public <T> T tx(Function<Db, T> work) {
        Objects.requireNonNull(work, "work");
        int before = operations.size();
        try {
            T r = work.apply(this);
            // Commit - zostawiamy wpisane operacje
            return r;
        } catch (RuntimeException ex) {
            // Rollback: cofnij wszystkie ops zapisane w tej transakcji.
            while (operations.size() > before) {
                operations.remove(operations.size() - 1);
            }
            throw ex;
        }
    }

    @Override
    public String tablePrefix() {
        return "";
    }

    public long nextGeneratedId() {
        return nextId.getAndIncrement();
    }
}

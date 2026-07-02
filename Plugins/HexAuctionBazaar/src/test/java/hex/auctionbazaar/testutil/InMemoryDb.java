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

    public void setDefaultUpdate(Handler h) { this.defaultUpdate = h; }
    public void setDefaultQuery(QueryHandler<Object> h) { this.defaultQuery = h; }

    /** Operacja logowana; test moze zweryfikowac czy wyladowala w liscie. */
    public record Op(String sql, List<Object> params) {}

    public List<Op> operations() { return List.copyOf(operations); }
    public void clearOps() { operations.clear(); }

    @Override
    public int update(String sql, Object... params) {
        operations.add(new Op(sql, List.of(params)));
        return defaultUpdate.handle(List.of(params));
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        operations.add(new Op(sql, List.of(params)));
        // Nie mamy sposobu na ResultSet - test double zwraca pusta liste.
        return List.of();
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        operations.add(new Op(sql, List.of(params)));
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

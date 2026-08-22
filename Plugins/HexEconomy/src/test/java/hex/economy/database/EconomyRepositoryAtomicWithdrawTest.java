package hex.economy.database;

import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EconomyRepositoryAtomicWithdrawTest {
    @Test
    void twoConcurrentWithdrawalsCannotSpendSameBalanceTwice() throws Exception {
        FakeDb db = new FakeDb();
        EconomyRepository repository = new EconomyRepository(db);
        UUID uuid = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<EconomyRepository.WithdrawResult> call = () -> {
            start.await();
            return repository.withdrawIfSufficient(uuid, "Player", new BigDecimal("5.00"), new BigDecimal("5.00"), false);
        };
        Future<EconomyRepository.WithdrawResult> first = executor.submit(call);
        Future<EconomyRepository.WithdrawResult> second = executor.submit(call);
        start.countDown();
        var a = first.get();
        var b = second.get();
        executor.shutdownNow();
        assertEquals(1, java.util.stream.Stream.of(a,b).filter(EconomyRepository.WithdrawResult::success).count());
        assertEquals(0, db.balances.get(uuid.toString()).compareTo(BigDecimal.ZERO));
    }

    private static final class FakeDb implements Db {
        final Map<String, BigDecimal> balances = new HashMap<>();
        final ReentrantLock lock = new ReentrantLock();
        public int update(String sql,Object... p) {
            if (sql.startsWith("INSERT IGNORE")) { balances.putIfAbsent((String)p[0], (BigDecimal)p[2]); return 1; }
            if (sql.contains("SET player_name=?, balance=?")) { balances.put((String)p[3], (BigDecimal)p[1]); return 1; }
            return 1;
        }
        public <T> List<T> query(String sql,RowMapper<T> mapper,Object... p){ return List.of(); }
        public <T> Optional<T> queryOne(String sql,RowMapper<T> mapper,Object... p) {
            BigDecimal value = balances.get((String)p[0]);
            if (value == null) return Optional.empty();
            try {
                ResultSet rs = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class}, (o,m,a) -> {
                    if (m.getName().equals("getBigDecimal")) return value;
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
                return Optional.ofNullable(mapper.map(rs));
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
        public int[] batch(String sql,List<Object[]> params){ return new int[0]; }
        public <T>T tx(Function<Db,T> work){ lock.lock(); try { return work.apply(this); } finally { lock.unlock(); } }
        public String tablePrefix(){ return ""; }
    }
}

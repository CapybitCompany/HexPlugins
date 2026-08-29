package hex.events.hardening;

import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import hex.events.persistence.EventSchedule7dRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

public final class EventSchedule7dRepositoryLogicTest {
    public static void main(String[] args) {
        FakeDb db = new FakeDb();
        EventSchedule7dRepository repo = new EventSchedule7dRepository(db);
        repo.ensureTable();

        UUID id = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        EventSchedule7dRepository.ScheduleRow row = new EventSchedule7dRepository.ScheduleRow(
                id, "end_opening", "End", LocalDateTime.of(2026, 8, 30, 17, 0, 12),
                Instant.parse("2026-08-30T15:00:12Z").getEpochSecond(), "Europe/Warsaw", "RECURRING", true);

        repo.replaceAll(List.of(row), Instant.parse("2026-08-29T10:00:01Z"), ZoneId.of("Europe/Warsaw"));

        if (!db.created) throw new AssertionError("schedule table was not created");
        if (!db.deletedInsideTx) throw new AssertionError("old 7-day snapshot must be deleted inside transaction");
        if (db.batchRows.size() != 1) throw new AssertionError("expected one published row");
        Object[] params = db.batchRows.getFirst();
        if (!id.toString().equals(params[0])) throw new AssertionError("instance UUID mismatch");
        if (!"end_opening".equals(params[1])) throw new AssertionError("event id mismatch");
        if (!"End".equals(params[2])) throw new AssertionError("event name mismatch");
        if (!"Europe/Warsaw".equals(params[5])) throw new AssertionError("timezone mismatch");
        if (!"RECURRING".equals(params[6]) || !Boolean.TRUE.equals(params[7])) throw new AssertionError("schedule type mismatch");
        if (((Number) params[4]).longValue() != Instant.parse("2026-08-30T15:00:12Z").getEpochSecond())
            throw new AssertionError("epoch-second precision mismatch");

        repo.replaceAll(List.of(), Instant.parse("2026-08-29T11:00:01Z"), ZoneId.of("Europe/Warsaw"));
        if (!db.batchRows.isEmpty()) throw new AssertionError("empty schedule must publish an empty table");

        System.out.println("EventSchedule7dRepositoryLogicTest OK");
    }

    private static final class FakeDb implements Db {
        boolean created;
        boolean deletedInsideTx;
        boolean inTx;
        List<Object[]> batchRows = new ArrayList<>();

        @Override public int update(String sql, Object... params) {
            if (sql.startsWith("CREATE TABLE IF NOT EXISTS")) created = true;
            if (sql.startsWith("DELETE FROM")) {
                if (!inTx) throw new AssertionError("DELETE must be transactional");
                deletedInsideTx = true;
                batchRows.clear();
            }
            return 1;
        }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) { return List.of(); }
        @Override public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) { return Optional.empty(); }
        @Override public int[] batch(String sql, List<Object[]> batchParams) {
            if (!inTx) throw new AssertionError("batch must be transactional");
            batchRows = new ArrayList<>(batchParams);
            return new int[batchParams.size()];
        }
        @Override public <T> T tx(Function<Db, T> work) {
            if (inTx) throw new AssertionError("nested tx not expected");
            inTx = true;
            try { return work.apply(this); }
            finally { inTx = false; }
        }
        @Override public String tablePrefix() { return "hex_"; }
    }
}

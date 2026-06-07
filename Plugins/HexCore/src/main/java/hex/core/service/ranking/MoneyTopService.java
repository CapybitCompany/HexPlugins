package hex.core.service.ranking;

import hex.core.api.db.Db;
import hex.core.database.model.MoneyTopEntry;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MoneyTopService {

    private static final int TOP_LIMIT = 10;
    private static final long TTL_MILLIS = 15_000L;

    private final Db db;
    private final String tableName;
    private final ConcurrentMap<Integer, CacheEntry> cache = new ConcurrentHashMap<>();

    public MoneyTopService(Db db) {
        this.db = db;
        this.tableName = db.t("vishop_player_totals");
    }

    public MoneyTopEntry getTop(int position) {
        if (position < 1 || position > TOP_LIMIT) {
            return empty();
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(TOP_LIMIT);
        if (cached == null || now >= cached.expiresAtMillis) {
            cached = refresh(now);
            cache.put(TOP_LIMIT, cached);
        }

        int index = position - 1;
        if (index >= cached.entries.size()) {
            return empty();
        }

        MoneyTopEntry entry = cached.entries.get(index);
        return entry == null ? empty() : entry;
    }

    private CacheEntry refresh(long now) {
        try {
            List<MoneyTopEntry> entries = db.query(
                    "SELECT uuid, player_name, total_spent FROM " + tableName + " ORDER BY total_spent DESC, player_name ASC LIMIT ?",
                    this::mapRow,
                    TOP_LIMIT
            );
            return new CacheEntry(entries == null ? Collections.emptyList() : entries, now + TTL_MILLIS);
        } catch (Exception ex) {
            return new CacheEntry(Collections.emptyList(), now + TTL_MILLIS);
        }
    }

    private MoneyTopEntry mapRow(ResultSet rs) throws SQLException {
        String playerName = rs.getString("player_name");
        BigDecimal totalSpent = rs.getBigDecimal("total_spent");
        if (totalSpent == null) {
            totalSpent = BigDecimal.ZERO;
        }

        String uuidRaw = null;
        try {
            uuidRaw = rs.getString("uuid");
        } catch (SQLException ignored) {
            // Kolumna uuid może być niewybierana w starszych schematach; ignorujemy.
        }

        UUID uuid = null;
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try {
                uuid = UUID.fromString(uuidRaw);
            } catch (IllegalArgumentException ignored) {
                uuid = null;
            }
        }

        return new MoneyTopEntry(uuid, playerName, totalSpent);
    }

    private static MoneyTopEntry empty() {
        return new MoneyTopEntry(null, "-", null);
    }

    private record CacheEntry(List<MoneyTopEntry> entries, long expiresAtMillis) {
    }
}


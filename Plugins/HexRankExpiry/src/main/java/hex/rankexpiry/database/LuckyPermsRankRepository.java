package hex.rankexpiry.database;

import hex.core.api.db.Db;
import hex.rankexpiry.config.RankExpirySettings;
import hex.rankexpiry.model.RankDefinition;
import hex.rankexpiry.model.RankExpiry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class LuckyPermsRankRepository {
    private final RankExpirySettings settings;
    private volatile String resolvedTable;

    public LuckyPermsRankRepository(RankExpirySettings settings) {
        this.settings = settings;
    }

    public Optional<RankExpiry> findActiveRank(Db db, UUID uuid, long nowEpochSeconds) {
        List<RankDefinition> ranks = settings.ranks();
        if (ranks.isEmpty()) {
            return Optional.empty();
        }

        RuntimeException lastTableMissing = null;
        for (String table : tableCandidates()) {
            try {
                Optional<RankExpiry> rank = findActiveRankInTable(db, table, uuid, nowEpochSeconds, ranks);
                resolvedTable = table;
                return rank;
            } catch (RuntimeException exception) {
                if (!isMissingTableError(exception)) {
                    throw exception;
                }
                lastTableMissing = exception;
            }
        }

        if (lastTableMissing != null) {
            throw lastTableMissing;
        }
        return Optional.empty();
    }

    private List<String> tableCandidates() {
        String active = resolvedTable;
        if (active == null || active.isBlank()) {
            return settings.userPermissionsTableCandidates();
        }

        List<String> candidates = new ArrayList<>();
        candidates.add(active);
        for (String candidate : settings.userPermissionsTableCandidates()) {
            if (candidates.stream().noneMatch(existing -> existing.equalsIgnoreCase(candidate))) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private Optional<RankExpiry> findActiveRankInTable(Db db, String table, UUID uuid, long nowEpochSeconds, List<RankDefinition> ranks) {

        String permissionPlaceholders = String.join(",", Collections.nCopies(ranks.size(), "?"));
        String sql = "SELECT permission, expiry FROM " + table + " " +
                "WHERE (uuid = ? OR uuid = ?) " +
                "AND LOWER(permission) IN (" + permissionPlaceholders + ") " +
                "AND value = 1 " +
                "AND expiry > ?";

        List<Object> params = new ArrayList<>();
        params.add(uuid.toString());
        params.add(uuid.toString().replace("-", ""));
        for (RankDefinition rank : ranks) {
            params.add(rank.permission());
        }
        params.add(nowEpochSeconds);

        Map<String, Long> maxExpiryByPermission = new HashMap<>();
        db.query(sql, rs -> {
            String permission = rs.getString("permission");
            long expiry = rs.getLong("expiry");
            if (permission != null && expiry > nowEpochSeconds) {
                maxExpiryByPermission.merge(permission.toLowerCase(Locale.ROOT), expiry, Math::max);
            }
            return null;
        }, params.toArray());

        for (RankDefinition rank : ranks) {
            Long expiry = maxExpiryByPermission.get(rank.permission());
            if (expiry != null) {
                return Optional.of(new RankExpiry(rank.permission(), rank.displayName(), expiry));
            }
        }

        return Optional.empty();
    }

    private static boolean isMissingTableError(RuntimeException exception) {
        String message = exception.getMessage();
        Throwable cause = exception.getCause();
        while ((message == null || message.isBlank()) && cause != null) {
            message = cause.getMessage();
            cause = cause.getCause();
        }
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("sqlstate=42s02")
                || lower.contains("code=1146")
                || lower.contains("doesn't exist")
                || lower.contains("does not exist")
                || lower.contains("no such table");
    }
}

package pl.hexnetwork.hexnametags.persistence;

import hex.core.api.db.Db;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class NameTagRepository {
    private final Db db;
    private final String table;

    public NameTagRepository(Db db, String tableName) {
        this.db = db;
        this.table = db.t(tableName);
    }

    public String table() {
        return table;
    }

    public void createTableIfMissing() {
        db.update("""
                CREATE TABLE IF NOT EXISTS %s (
                  target_uuid VARCHAR(36) PRIMARY KEY,
                  target_type VARCHAR(16) NOT NULL,
                  lines_data TEXT NOT NULL,
                  style_key VARCHAR(64) NOT NULL,
                  enabled INTEGER NOT NULL,
                  updated_at BIGINT NOT NULL
                )
                """.formatted(table));
    }

    public Optional<PersistedNameTag> findEnabled(UUID targetUuid) {
        return db.queryOne("""
                SELECT target_uuid, target_type, lines_data, style_key, enabled, updated_at
                FROM %s
                WHERE target_uuid = ? AND enabled = 1
                """.formatted(table), rs -> {
            UUID uuid = UUID.fromString(rs.getString("target_uuid"));
            PersistedNameTag.TargetType targetType = parseType(rs.getString("target_type"));
            List<Component> lines = NameTagCodec.decode(rs.getString("lines_data"));
            String styleKey = rs.getString("style_key");
            boolean enabled = rs.getInt("enabled") != 0;
            long updatedAt = rs.getLong("updated_at");
            return new PersistedNameTag(uuid, targetType, lines, styleKey, enabled, updatedAt);
        }, targetUuid.toString());
    }

    public void upsertPlayer(UUID targetUuid, List<Component> lines, String styleKey) {
        long now = System.currentTimeMillis();
        String encodedLines = NameTagCodec.encode(lines);
        String effectiveStyleKey = styleKey == null || styleKey.isBlank() ? "default" : styleKey;

        int updated = db.update("""
                UPDATE %s
                SET target_type = ?, lines_data = ?, style_key = ?, enabled = ?, updated_at = ?
                WHERE target_uuid = ?
                """.formatted(table),
                PersistedNameTag.TargetType.PLAYER.name(), encodedLines, effectiveStyleKey, 1, now, targetUuid.toString());

        if (updated == 0) {
            db.update("""
                    INSERT INTO %s (target_uuid, target_type, lines_data, style_key, enabled, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.formatted(table),
                    targetUuid.toString(), PersistedNameTag.TargetType.PLAYER.name(), encodedLines, effectiveStyleKey, 1, now);
        }
    }

    public void delete(UUID targetUuid) {
        db.update("DELETE FROM %s WHERE target_uuid = ?".formatted(table), targetUuid.toString());
    }

    private static PersistedNameTag.TargetType parseType(String value) {
        if (value == null || value.isBlank()) {
            return PersistedNameTag.TargetType.PLAYER;
        }
        try {
            return PersistedNameTag.TargetType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return PersistedNameTag.TargetType.PLAYER;
        }
    }
}

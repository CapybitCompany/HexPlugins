package hex.skills.database;

import hex.core.api.db.Db;
import hex.skills.model.SkillDefinition;

import java.util.Optional;
import java.util.UUID;

public final class SkillRepository {
    private final Db db;

    public SkillRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("skills_progress") + " (" +
                "town_id VARCHAR(36) NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "skill_id VARCHAR(128) NOT NULL," +
                "xp BIGINT NOT NULL DEFAULT 0," +
                "level INT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, player_uuid, skill_id)," +
                "KEY idx_player (player_uuid)," +
                "KEY idx_town (town_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public Progress addXp(UUID townId, UUID playerUuid, SkillDefinition skill, long delta) {
        if (delta <= 0L) {
            return getProgress(townId, playerUuid, skill.id()).orElse(new Progress(0L, 0));
        }
        return db.tx(tx -> {
            Optional<Progress> existing = tx.queryOne("SELECT xp, level FROM " + tx.t("skills_progress") +
                            " WHERE town_id=? AND player_uuid=? AND skill_id=?",
                    rs -> new Progress(rs.getLong("xp"), rs.getInt("level")),
                    townId.toString(), playerUuid.toString(), skill.id());
            Progress current = existing.orElse(new Progress(0L, 0));

            long xp = current.xp() + delta;
            int level = skill.levelForXp(xp);
            if (existing.isEmpty()) {
                tx.update("INSERT INTO " + tx.t("skills_progress") +
                                " (town_id, player_uuid, skill_id, xp, level, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                        townId.toString(), playerUuid.toString(), skill.id(), xp, level, System.currentTimeMillis());
            } else {
                tx.update("UPDATE " + tx.t("skills_progress") +
                                " SET xp=?, level=?, updated_at=? WHERE town_id=? AND player_uuid=? AND skill_id=?",
                        xp, level, System.currentTimeMillis(), townId.toString(), playerUuid.toString(), skill.id());
            }
            return new Progress(xp, level);
        });
    }

    public Optional<Progress> getProgress(UUID townId, UUID playerUuid, String skillId) {
        return db.queryOne("SELECT xp, level FROM " + db.t("skills_progress") +
                        " WHERE town_id=? AND player_uuid=? AND skill_id=?",
                rs -> new Progress(rs.getLong("xp"), rs.getInt("level")),
                townId.toString(), playerUuid.toString(), skillId);
    }

    public void purgeTown(UUID townId) {
        db.update("DELETE FROM " + db.t("skills_progress") + " WHERE town_id=?", townId.toString());
    }

    public record Progress(long xp, int level) {
    }
}



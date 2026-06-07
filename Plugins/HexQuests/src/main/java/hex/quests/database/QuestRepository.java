package hex.quests.database;

import hex.core.api.db.Db;
import hex.quests.model.QuestDefinition;
import hex.quests.model.QuestObjective;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class QuestRepository {
    private final Db db;

    public QuestRepository(Db db) {
        this.db = db;
    }

    public void ensureTables() {
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("quests_progress") + " (" +
                "town_id VARCHAR(36) NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "quest_id VARCHAR(128) NOT NULL," +
                "assigned_for_date VARCHAR(10) NOT NULL," +
                "state VARCHAR(32) NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, player_uuid, quest_id, assigned_for_date)," +
                "KEY idx_town_player (town_id, player_uuid)," +
                "KEY idx_date (assigned_for_date)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");

        db.update("CREATE TABLE IF NOT EXISTS " + db.t("quest_objective_progress") + " (" +
                "town_id VARCHAR(36) NOT NULL," +
                "player_uuid VARCHAR(36) NOT NULL," +
                "quest_id VARCHAR(128) NOT NULL," +
                "assigned_for_date VARCHAR(10) NOT NULL," +
                "objective_id VARCHAR(128) NOT NULL," +
                "amount BIGINT NOT NULL DEFAULT 0," +
                "updated_at BIGINT NOT NULL," +
                "PRIMARY KEY (town_id, player_uuid, quest_id, assigned_for_date, objective_id)," +
                "KEY idx_quest (quest_id)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public void ensureDailyAssigned(UUID townId, UUID playerUuid, LocalDate date, List<QuestDefinition> selected) {
        String day = date.toString();
        db.tx(tx -> {
            for (QuestDefinition quest : selected) {
                boolean exists = tx.queryOne("SELECT quest_id FROM " + tx.t("quests_progress") +
                                " WHERE town_id=? AND player_uuid=? AND quest_id=? AND assigned_for_date=?",
                        rs -> rs.getString("quest_id"), townId.toString(), playerUuid.toString(), quest.id(), day).isPresent();
                if (!exists) {
                    tx.update("INSERT INTO " + tx.t("quests_progress") +
                                    " (town_id, player_uuid, quest_id, assigned_for_date, state, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                            townId.toString(), playerUuid.toString(), quest.id(), day, "ACTIVE", System.currentTimeMillis());
                    for (QuestObjective objective : quest.objectives()) {
                        tx.update("INSERT INTO " + tx.t("quest_objective_progress") +
                                        " (town_id, player_uuid, quest_id, assigned_for_date, objective_id, amount, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                townId.toString(), playerUuid.toString(), quest.id(), day, objective.id(), 0L, System.currentTimeMillis());
                    }
                }
            }
            return null;
        });
    }

    public List<String> activeQuestIds(UUID townId, UUID playerUuid, LocalDate date) {
        return db.query("SELECT quest_id FROM " + db.t("quests_progress") +
                        " WHERE town_id=? AND player_uuid=? AND assigned_for_date=? AND state='ACTIVE'",
                rs -> rs.getString("quest_id"), townId.toString(), playerUuid.toString(), date.toString());
    }

    public long incrementObjective(UUID townId, UUID playerUuid, LocalDate date, String questId, QuestObjective objective, long delta) {
        return db.tx(tx -> {
            Long current = tx.queryOne("SELECT amount FROM " + tx.t("quest_objective_progress") +
                            " WHERE town_id=? AND player_uuid=? AND quest_id=? AND assigned_for_date=? AND objective_id=?",
                    rs -> rs.getLong("amount"), townId.toString(), playerUuid.toString(), questId, date.toString(), objective.id()).orElse(0L);
            long amount = Math.min(objective.amount(), current + Math.max(1L, delta));
            tx.update("UPDATE " + tx.t("quest_objective_progress") +
                            " SET amount=?, updated_at=? WHERE town_id=? AND player_uuid=? AND quest_id=? AND assigned_for_date=? AND objective_id=?",
                    amount, System.currentTimeMillis(), townId.toString(), playerUuid.toString(), questId, date.toString(), objective.id());
            return amount;
        });
    }

    public boolean isComplete(UUID townId, UUID playerUuid, LocalDate date, QuestDefinition quest) {
        for (QuestObjective objective : quest.objectives()) {
            long amount = db.queryOne("SELECT amount FROM " + db.t("quest_objective_progress") +
                            " WHERE town_id=? AND player_uuid=? AND quest_id=? AND assigned_for_date=? AND objective_id=?",
                    rs -> rs.getLong("amount"), townId.toString(), playerUuid.toString(), quest.id(), date.toString(), objective.id()).orElse(0L);
            if (amount < objective.amount()) {
                return false;
            }
        }
        return !quest.objectives().isEmpty();
    }

    public void markCompleted(UUID townId, UUID playerUuid, LocalDate date, String questId) {
        db.update("UPDATE " + db.t("quests_progress") +
                        " SET state='COMPLETED', updated_at=? WHERE town_id=? AND player_uuid=? AND quest_id=? AND assigned_for_date=?",
                System.currentTimeMillis(), townId.toString(), playerUuid.toString(), questId, date.toString());
    }

    public void purgeTown(UUID townId) {
        db.update("DELETE FROM " + db.t("quest_objective_progress") + " WHERE town_id=?", townId.toString());
        db.update("DELETE FROM " + db.t("quests_progress") + " WHERE town_id=?", townId.toString());
    }
}


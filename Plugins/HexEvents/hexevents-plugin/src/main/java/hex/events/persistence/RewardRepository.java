package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.api.*;
import hex.events.reward.RewardPlanEntry;
import hex.events.util.ReceiptCodec;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Durable, idempotent reward ledger. Ambiguous DELIVERING rows are never blindly retried after crash. */
public final class RewardRepository {
    private final Db db;
    public RewardRepository(Db db){ this.db=db; }

    public void ensureTable(){
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_rewards") + " ("+
                "reward_uuid CHAR(36) NOT NULL,instance_uuid CHAR(36) NOT NULL,event_id VARCHAR(96) NOT NULL,"+
                "subject_type VARCHAR(24) NOT NULL,subject_uuid CHAR(36) NOT NULL,subject_name VARCHAR(64) NULL,"+
                "rule_id VARCHAR(96) NOT NULL,grant_index INT NOT NULL,provider VARCHAR(64) NOT NULL,amount VARCHAR(64) NOT NULL,"+
                "payload_data LONGTEXT NOT NULL,idempotency_key VARCHAR(191) NOT NULL,status VARCHAR(40) NOT NULL,attempts INT NOT NULL DEFAULT 0,"+
                "last_error TEXT NULL,created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,delivered_at BIGINT NULL,"+
                "PRIMARY KEY(reward_uuid),UNIQUE KEY uq_event_reward_idem(idempotency_key),KEY idx_reward_player(subject_uuid,status),KEY idx_reward_instance(instance_uuid,status)"+
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public void recoverAmbiguousOperations(){
        db.update("UPDATE " + db.t("event_rewards") + " SET status='RECONCILIATION_REQUIRED',last_error='SERVER_CRASH_DURING_DELIVERY',updated_at=? WHERE status='DELIVERING'", System.currentTimeMillis());
    }

    public void insertPlanned(UUID instanceId, String eventId, String subjectName, RewardPlanEntry entry){
        String idem = instanceId + ":" + entry.subject().id() + ":" + entry.ruleId() + ":" + entry.grantIndex();
        UUID id = UUID.nameUUIDFromBytes(("hexevent-reward:"+idem).getBytes(StandardCharsets.UTF_8));
        Map<String,String> payload = new LinkedHashMap<>();
        entry.grant().settings().asMap().forEach((k,v)->payload.put(k,String.valueOf(v)));
        long now=System.currentTimeMillis();
        db.update("INSERT IGNORE INTO " + db.t("event_rewards") + " (reward_uuid,instance_uuid,event_id,subject_type,subject_uuid,subject_name,rule_id,grant_index,provider,amount,payload_data,idempotency_key,status,attempts,last_error,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id.toString(),instanceId.toString(),eventId,entry.subject().type().name(),entry.subject().id().toString(),subjectName,
                entry.ruleId(),entry.grantIndex(),entry.grant().type(),entry.grant().amount().toPlainString(),ReceiptCodec.encode(payload),idem,"PENDING",0,"",now,now);
    }

    public List<RewardRow> loadDeliverableForInstance(UUID instanceId){
        return query(" WHERE instance_uuid=? AND status IN ('PENDING','FAILED_RETRYABLE') ORDER BY created_at,grant_index", instanceId.toString());
    }
    public List<RewardRow> loadDeliverableForPlayer(UUID playerId){
        return query(" WHERE subject_uuid=? AND subject_type='PLAYER' AND status IN ('PENDING','FAILED_RETRYABLE') ORDER BY created_at,grant_index", playerId.toString());
    }
    public List<RewardRow> loadForInstance(UUID instanceId){ return query(" WHERE instance_uuid=? ORDER BY created_at,grant_index", instanceId.toString()); }

    private List<RewardRow> query(String where,Object...args){
        return db.query("SELECT * FROM " + db.t("event_rewards") + where, rs -> new RewardRow(
                UUID.fromString(rs.getString("reward_uuid")),UUID.fromString(rs.getString("instance_uuid")),rs.getString("event_id"),
                ResultSubjectType.valueOf(rs.getString("subject_type")),UUID.fromString(rs.getString("subject_uuid")),rs.getString("subject_name"),
                rs.getString("rule_id"),rs.getInt("grant_index"),rs.getString("provider"),new java.math.BigDecimal(rs.getString("amount")),
                new EventModuleSettings(ReceiptCodec.decode(rs.getString("payload_data"))),rs.getString("idempotency_key"),rs.getString("status"),rs.getInt("attempts"),rs.getString("last_error")),args);
    }


    public void cancelPending(UUID instanceId,String reason){
        db.update("UPDATE " + db.t("event_rewards") + " SET status='CANCELLED',last_error=?,updated_at=? WHERE instance_uuid=? AND status IN ('PENDING','FAILED_RETRYABLE')",
                trim(reason),System.currentTimeMillis(),instanceId.toString());
    }

    public boolean claim(UUID rewardId){
        return db.update("UPDATE " + db.t("event_rewards") + " SET status='DELIVERING',attempts=attempts+1,updated_at=? WHERE reward_uuid=? AND status IN ('PENDING','FAILED_RETRYABLE')",
                System.currentTimeMillis(),rewardId.toString())>0;
    }
    public void delivered(UUID rewardId){ long now=System.currentTimeMillis(); db.update("UPDATE "+db.t("event_rewards")+" SET status='DELIVERED',last_error='',updated_at=?,delivered_at=? WHERE reward_uuid=? AND status='DELIVERING'",now,now,rewardId.toString()); }
    public void retry(UUID rewardId,String error){ db.update("UPDATE "+db.t("event_rewards")+" SET status='FAILED_RETRYABLE',last_error=?,updated_at=? WHERE reward_uuid=? AND status='DELIVERING'",trim(error),System.currentTimeMillis(),rewardId.toString()); }
    public void failed(UUID rewardId,String error){ db.update("UPDATE "+db.t("event_rewards")+" SET status='FAILED_PERMANENT',last_error=?,updated_at=? WHERE reward_uuid=? AND status='DELIVERING'",trim(error),System.currentTimeMillis(),rewardId.toString()); }
    public void reconcile(UUID rewardId,String error){ db.update("UPDATE "+db.t("event_rewards")+" SET status='RECONCILIATION_REQUIRED',last_error=?,updated_at=? WHERE reward_uuid=?",trim(error),System.currentTimeMillis(),rewardId.toString()); }
    private static String trim(String s){ if(s==null)return ""; return s.length()>1000?s.substring(0,1000):s; }

    public record RewardRow(UUID rewardId,UUID instanceId,String eventId,ResultSubjectType subjectType,UUID subjectId,String subjectName,
                            String ruleId,int grantIndex,String provider,java.math.BigDecimal amount,EventModuleSettings settings,String idempotencyKey,
                            String status,int attempts,String lastError){
        public RewardContext context(){ return new RewardContext(instanceId,eventId,subjectId,subjectType,subjectName); }
        public RewardGrant grant(){ return new RewardGrant(provider,amount,settings); }
    }
}

package hex.events.persistence;

import hex.core.api.db.Db;
import hex.events.api.ResultSubject;
import hex.events.reward.ProcessedResult;
import hex.events.util.ReceiptCodec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class ResultRepository {
    private final Db db;
    public ResultRepository(Db db){ this.db = db; }

    public void ensureTables(){
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_result_batches") + " (" +
                "instance_uuid CHAR(36) NOT NULL,outcome VARCHAR(32) NOT NULL,metadata_data LONGTEXT NULL,created_at BIGINT NOT NULL,"+
                "PRIMARY KEY(instance_uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("event_results") + " (" +
                "instance_uuid CHAR(36) NOT NULL,subject_type VARCHAR(24) NOT NULL,subject_uuid CHAR(36) NOT NULL,"+
                "rank_value INT NULL,score DOUBLE NOT NULL DEFAULT 0,metrics_data LONGTEXT NOT NULL,tags_data LONGTEXT NULL,created_at BIGINT NOT NULL,"+
                "PRIMARY KEY(instance_uuid,subject_type,subject_uuid),KEY idx_event_results_instance(instance_uuid,rank_value)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
    }

    public void save(java.util.UUID instanceId, ProcessedResult result){
        long now = System.currentTimeMillis();
        db.tx(tx -> {
            tx.update("INSERT INTO " + tx.t("event_result_batches") + " (instance_uuid,outcome,metadata_data,created_at) VALUES (?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE outcome=VALUES(outcome),metadata_data=VALUES(metadata_data)",
                    instanceId.toString(), result.outcome().name(), ReceiptCodec.encode(result.metadata()), now);
            for(ResultSubject s: result.subjects()){
                Map<String,String> metrics = new LinkedHashMap<>();
                s.metrics().forEach((k,v)->metrics.put(k,Double.toString(v)));
                int rank = (int)Math.round(s.metrics().getOrDefault("rank", 0.0));
                double score = s.metrics().getOrDefault("damage", s.metrics().getOrDefault("score", 0.0));
                String tags = s.tags().stream().sorted().collect(Collectors.joining("\n"));
                tx.update("INSERT INTO " + tx.t("event_results") + " (instance_uuid,subject_type,subject_uuid,rank_value,score,metrics_data,tags_data,created_at) VALUES (?,?,?,?,?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE rank_value=VALUES(rank_value),score=VALUES(score),metrics_data=VALUES(metrics_data),tags_data=VALUES(tags_data)",
                        instanceId.toString(), s.type().name(), s.id().toString(), rank<=0?null:rank, score, ReceiptCodec.encode(metrics), tags, now);
            }
            return null;
        });
    }
}

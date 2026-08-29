package hex.events.api;

import java.time.Instant;
import java.util.UUID;

public interface ModifierApi {
    double combinedPercent(ResultSubjectType type, UUID subjectId, String key);
    void add(ResultSubjectType type, UUID subjectId, String key, double percent, Instant expiresAt, String source);
}

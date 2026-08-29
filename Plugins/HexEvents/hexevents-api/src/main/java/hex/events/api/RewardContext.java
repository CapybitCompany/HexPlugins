package hex.events.api;

import java.util.UUID;

public record RewardContext(UUID instanceId, String eventId, UUID subjectId, ResultSubjectType subjectType, String subjectName) { }

package hex.quests.model;

import java.util.Map;

public record QuestProgressSnapshot(
        String questId,
        String state,
        boolean rewarded,
        Map<String, Long> objectiveProgress
) {
    public QuestProgressSnapshot {
        objectiveProgress = Map.copyOf(objectiveProgress);
    }

    public boolean completed() {
        return "COMPLETED".equalsIgnoreCase(state);
    }
}

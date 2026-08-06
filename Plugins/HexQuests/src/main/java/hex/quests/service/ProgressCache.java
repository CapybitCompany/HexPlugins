package hex.quests.service;

import hex.quests.model.QuestProgressSnapshot;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

public final class ProgressCache {
    private final ConcurrentMap<Key, ConcurrentMap<String, MutableQuestProgress>> cache = new ConcurrentHashMap<>();
    private final Set<Key> loaded = ConcurrentHashMap.newKeySet();

    public void replace(UUID playerUuid, LocalDate date, Map<String, QuestProgressSnapshot> snapshots) {
        Key key = new Key(playerUuid, date);
        ConcurrentMap<String, MutableQuestProgress> values = cache.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
        snapshots.forEach((questId, snapshot) -> values.merge(questId, MutableQuestProgress.from(snapshot),
                MutableQuestProgress::merge));
        loaded.add(key);
    }

    public boolean isLoaded(UUID playerUuid, LocalDate date) {
        return loaded.contains(new Key(playerUuid, date));
    }

    public void ensureQuest(UUID playerUuid, LocalDate date, String questId) {
        questMap(playerUuid, date).computeIfAbsent(questId, ignored -> new MutableQuestProgress());
    }

    public long incrementLocal(UUID playerUuid, LocalDate date, String questId, String objectiveId, long delta, long target) {
        MutableQuestProgress progress = questMap(playerUuid, date)
                .computeIfAbsent(questId, ignored -> new MutableQuestProgress());
        long positiveDelta = Math.max(0L, delta);
        return progress.objectives.merge(objectiveId, Math.min(target, positiveDelta),
                (oldValue, ignored) -> cappedAdd(oldValue, positiveDelta, target));
    }

    public void reconcile(UUID playerUuid, LocalDate date, String questId, String objectiveId,
                          long amount, boolean completed) {
        MutableQuestProgress progress = questMap(playerUuid, date)
                .computeIfAbsent(questId, ignored -> new MutableQuestProgress());
        progress.objectives.merge(objectiveId, amount, Math::max);
        if (completed) progress.state = "COMPLETED";
    }

    public void markRewarded(UUID playerUuid, LocalDate date, String questId) {
        MutableQuestProgress progress = questMap(playerUuid, date)
                .computeIfAbsent(questId, ignored -> new MutableQuestProgress());
        progress.rewarded = true;
        progress.state = "COMPLETED";
    }

    public Map<String, QuestProgressSnapshot> snapshot(UUID playerUuid, LocalDate date) {
        Map<String, QuestProgressSnapshot> result = new LinkedHashMap<>();
        ConcurrentMap<String, MutableQuestProgress> values = cache.get(new Key(playerUuid, date));
        if (values == null) return Map.of();
        values.forEach((questId, progress) -> result.put(questId, progress.snapshot(questId)));
        return Map.copyOf(result);
    }

    public void invalidatePlayer(UUID playerUuid) {
        cache.keySet().removeIf(key -> key.playerUuid.equals(playerUuid));
        loaded.removeIf(key -> key.playerUuid.equals(playerUuid));
    }

    public void clear() {
        cache.clear();
        loaded.clear();
    }

    private ConcurrentMap<String, MutableQuestProgress> questMap(UUID playerUuid, LocalDate date) {
        return cache.computeIfAbsent(new Key(playerUuid, date), ignored -> new ConcurrentHashMap<>());
    }

    private static long cappedAdd(long current, long delta, long target) {
        long remaining = Math.max(0L, target - current);
        return delta >= remaining ? target : current + delta;
    }

    private record Key(UUID playerUuid, LocalDate date) {}

    private static final class MutableQuestProgress {
        private volatile String state = "ACTIVE";
        private volatile boolean rewarded;
        private final ConcurrentMap<String, Long> objectives = new ConcurrentHashMap<>();

        static MutableQuestProgress from(QuestProgressSnapshot snapshot) {
            MutableQuestProgress result = new MutableQuestProgress();
            result.state = snapshot.state();
            result.rewarded = snapshot.rewarded();
            result.objectives.putAll(snapshot.objectiveProgress());
            return result;
        }

        static MutableQuestProgress merge(MutableQuestProgress left, MutableQuestProgress right) {
            if ("COMPLETED".equalsIgnoreCase(right.state)) left.state = "COMPLETED";
            left.rewarded = left.rewarded || right.rewarded;
            right.objectives.forEach((id, amount) -> left.objectives.merge(id, amount, Math::max));
            return left;
        }

        QuestProgressSnapshot snapshot(String questId) {
            return new QuestProgressSnapshot(questId, state, rewarded, new LinkedHashMap<>(objectives));
        }
    }
}

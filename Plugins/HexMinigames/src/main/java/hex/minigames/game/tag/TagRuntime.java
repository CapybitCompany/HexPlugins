package hex.minigames.game.tag;

import java.util.*;

/** Tag transfers, immunity and accumulated runner time, independent of Bukkit. */
public final class TagRuntime {
    private final Map<UUID, State> players = new LinkedHashMap<>();
    private final Random random;
    private final int immunityTicks;
    private final long endTick;

    public TagRuntime(Collection<UUID> participants, Random random, int immunityTicks, long startTick, long durationTicks) {
        this.random = random;
        this.immunityTicks = immunityTicks;
        this.endTick = startTick + durationTicks;
        for (UUID id : participants) players.put(id, new State(startTick));
        rebalance(startTick);
    }

    public static int taggerCount(int players) {
        return players < 2 ? 0 : players <= 4 ? 1 : players <= 8 ? 2 : 3;
    }

    public boolean isTagger(UUID id) {
        State state = players.get(id);
        return state != null && state.tagger;
    }

    public Set<UUID> taggers() {
        Set<UUID> result = new LinkedHashSet<>();
        players.forEach((id, state) -> { if (state.tagger) result.add(id); });
        return result;
    }

    public boolean transfer(UUID attacker, UUID target, long tick) {
        State from = players.get(attacker);
        State to = players.get(target);
        if (tick >= endTick || from == null || to == null || !from.tagger || to.tagger || tick < to.immuneUntil) return false;
        setTagger(from, false, tick);
        setTagger(to, true, tick);
        from.immuneUntil = tick + immunityTicks;
        return true;
    }

    public Set<UUID> remove(UUID id, long tick) {
        players.remove(id);
        return rebalance(tick);
    }

    public long runnerTicks(UUID id, long tick) {
        State state = players.get(id);
        if (state == null) return 0;
        return state.runnerTicks + (state.tagger ? 0 : Math.max(0, Math.min(tick, endTick) - state.updatedAt));
    }

    public int points(UUID id, long tick, List<Integer> thresholdsSeconds) {
        long survived = runnerTicks(id, tick);
        int points = 0;
        for (int seconds : thresholdsSeconds) if (survived >= seconds * 20L) points++;
        return points;
    }

    private Set<UUID> rebalance(long tick) {
        Set<UUID> newTaggers = new LinkedHashSet<>();
        int desired = taggerCount(players.size());
        List<UUID> candidates = new ArrayList<>(players.keySet());
        Collections.shuffle(candidates, random);
        for (UUID id : candidates) {
            if (taggers().size() >= desired) break;
            if (!isTagger(id)) {
                setTagger(players.get(id), true, tick);
                newTaggers.add(id);
            }
        }
        for (UUID id : candidates) {
            if (taggers().size() <= desired) break;
            if (isTagger(id)) {
                setTagger(players.get(id), false, tick);
                players.get(id).immuneUntil = tick + immunityTicks;
            }
        }
        return newTaggers;
    }

    private void setTagger(State state, boolean tagger, long tick) {
        long now = Math.min(tick, endTick);
        if (!state.tagger) state.runnerTicks += Math.max(0, now - state.updatedAt);
        state.updatedAt = now;
        state.tagger = tagger;
    }

    private static final class State {
        private boolean tagger;
        private long updatedAt;
        private long runnerTicks;
        private long immuneUntil;
        private State(long tick) { updatedAt = tick; }
    }
}

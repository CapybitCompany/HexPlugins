package hexbuildbattle.score;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.rating.RatingDefinition;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoundBuildScore {

    private final UUID ownerId;
    private final String ownerName;
    private final Arena arena;
    private final Map<UUID, RatingDefinition> votes = new HashMap<>();
    private final Set<UUID> goatEffectTriggeredBy = ConcurrentHashMap.newKeySet();

    public RoundBuildScore(UUID ownerId, String ownerName, Arena arena) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.arena = arena;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public Arena arena() {
        return arena;
    }

    public void vote(UUID voterId, RatingDefinition rating) {
        votes.put(voterId, rating);
    }

    public int totalPoints() {
        return votes.values().stream().mapToInt(RatingDefinition::points).sum();
    }

    public int validVoteCount() {
        return votes.size();
    }

    public long countRatingLevel(int level) {
        return votes.values().stream().filter(rating -> rating.level() == level).count();
    }

    public Map<UUID, RatingDefinition> votes() {
        return Collections.unmodifiableMap(votes);
    }

    public boolean markGoatEffectTriggered(UUID voterId) {
        return goatEffectTriggeredBy.add(voterId);
    }
}

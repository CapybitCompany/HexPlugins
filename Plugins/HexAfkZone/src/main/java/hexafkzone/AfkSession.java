package hexafkzone;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class AfkSession {

    private final UUID playerId;
    private final String profileId;
    private final String rewardGroupId;
    private final Instant enteredAt;
    private final Set<String> claimedMilestones = new HashSet<>();
    private Instant rewardMessageUntil;
    private String rewardMessage;

    AfkSession(UUID playerId, String profileId, String rewardGroupId, Instant enteredAt) {
        this.playerId = playerId;
        this.profileId = profileId;
        this.rewardGroupId = rewardGroupId;
        this.enteredAt = enteredAt;
    }

    UUID playerId() {
        return playerId;
    }

    String profileId() {
        return profileId;
    }

    String rewardGroupId() {
        return rewardGroupId;
    }

    Instant enteredAt() {
        return enteredAt;
    }

    Set<String> claimedMilestones() {
        return claimedMilestones;
    }

    Instant rewardMessageUntil() {
        return rewardMessageUntil;
    }

    void rewardMessageUntil(Instant rewardMessageUntil) {
        this.rewardMessageUntil = rewardMessageUntil;
    }

    String rewardMessage() {
        return rewardMessage;
    }

    void rewardMessage(String rewardMessage) {
        this.rewardMessage = rewardMessage;
    }
}

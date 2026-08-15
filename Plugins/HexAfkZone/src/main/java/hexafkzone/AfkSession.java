package hexafkzone;

import java.time.Instant;
import java.util.UUID;

final class AfkSession {

    private final UUID playerId;
    private final String profileId;
    private final Instant enteredAt;
    private Instant nextRewardAt;
    private Instant rewardMessageUntil;
    private String rewardMessage;

    AfkSession(UUID playerId, String profileId, Instant enteredAt, Instant nextRewardAt) {
        this.playerId = playerId;
        this.profileId = profileId;
        this.enteredAt = enteredAt;
        this.nextRewardAt = nextRewardAt;
    }

    UUID playerId() {
        return playerId;
    }

    String profileId() {
        return profileId;
    }

    Instant enteredAt() {
        return enteredAt;
    }

    Instant nextRewardAt() {
        return nextRewardAt;
    }

    void nextRewardAt(Instant nextRewardAt) {
        this.nextRewardAt = nextRewardAt;
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

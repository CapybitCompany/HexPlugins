package hex.collections.model;

public record CollectionScalingState(
        String collectionId,
        int targetLevel,
        int effectiveMemberCount
) {
    public CollectionScalingState {
        targetLevel = Math.max(1, targetLevel);
        effectiveMemberCount = Math.max(1, effectiveMemberCount);
    }

    public CollectionScalingState withMembers(int members) {
        return new CollectionScalingState(collectionId, targetLevel, Math.max(effectiveMemberCount, Math.max(1, members)));
    }
}

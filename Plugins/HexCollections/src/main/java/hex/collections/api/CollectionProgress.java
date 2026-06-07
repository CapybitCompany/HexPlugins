package hex.collections.api;

public record CollectionProgress(String collectionId, long amount, int level) {
    public static CollectionProgress empty(String collectionId) {
        return new CollectionProgress(collectionId, 0L, 0);
    }
}


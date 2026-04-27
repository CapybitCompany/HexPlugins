package hex.core.database.model;

/**
 * Player position in ranking (1-based). If not ranked, position is -1.
 */
public record RankingPosition(int position) {
    public boolean isRanked() {
        return position > 0;
    }
}


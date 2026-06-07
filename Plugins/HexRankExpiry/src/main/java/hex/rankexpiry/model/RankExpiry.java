package hex.rankexpiry.model;

public record RankExpiry(String permission, String displayName, long expiryEpochSeconds) {
    public long secondsRemaining(long nowEpochSeconds) {
        return Math.max(0L, expiryEpochSeconds - nowEpochSeconds);
    }

    public long daysRemaining(long nowEpochSeconds) {
        return daysRemaining(nowEpochSeconds, expiryEpochSeconds);
    }

    public boolean activeAt(long nowEpochSeconds) {
        return expiryEpochSeconds > nowEpochSeconds;
    }

    public static long daysRemaining(long nowEpochSeconds, long expiryEpochSeconds) {
        long remainingSeconds = Math.max(0L, expiryEpochSeconds - nowEpochSeconds);
        if (remainingSeconds == 0L) {
            return 0L;
        }
        return (remainingSeconds + 86_399L) / 86_400L;
    }
}

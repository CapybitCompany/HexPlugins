package hexdailyrewards;

public record ClaimResult(Status status, ClaimState state, String errorMessage) {

    public enum Status {
        CLAIMED,
        UNAVAILABLE,
        DISABLED,
        NO_REWARD,
        ERROR
    }

    public boolean claimed() {
        return status == Status.CLAIMED;
    }
}

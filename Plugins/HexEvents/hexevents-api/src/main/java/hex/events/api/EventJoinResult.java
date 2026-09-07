package hex.events.api;

public record EventJoinResult(Status status, String message) {
    public enum Status { JOINED, ALREADY_JOINED, DENIED, FULL, NOT_RUNNING, MODULE_UNAVAILABLE, ERROR }
    public boolean success() { return status == Status.JOINED || status == Status.ALREADY_JOINED; }
    public static EventJoinResult joined() { return new EventJoinResult(Status.JOINED, "OK"); }
    public static EventJoinResult alreadyJoined() { return new EventJoinResult(Status.ALREADY_JOINED, "ALREADY_JOINED"); }
    public static EventJoinResult denied(String message) { return new EventJoinResult(Status.DENIED, message); }
    public static EventJoinResult error(String message) { return new EventJoinResult(Status.ERROR, message); }
}

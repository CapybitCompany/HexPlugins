package hexnpc.workflow.input;

public record AnvilInputResult(Status status, String value) {
    public enum Status { SUBMITTED, CANCELLED }
    public static AnvilInputResult submitted(String value) { return new AnvilInputResult(Status.SUBMITTED, value == null ? "" : value); }
    public static AnvilInputResult cancelled() { return new AnvilInputResult(Status.CANCELLED, ""); }
}

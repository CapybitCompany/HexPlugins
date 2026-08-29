package hex.bossfight.engine;

public record AdapterHealth(Status status, String message) {
    public enum Status { READY, DEPENDENCY_UNAVAILABLE, UNSUPPORTED_VERSION, INCOMPATIBLE, MISCONFIGURED }
    public boolean ready(){ return status == Status.READY; }
    public static AdapterHealth ok(){ return new AdapterHealth(Status.READY,"OK"); }
}

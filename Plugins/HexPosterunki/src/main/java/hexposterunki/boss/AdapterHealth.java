package hexposterunki.boss;

/** Health of an external boss engine bridge. Anything but READY stops the event operation. */
public record AdapterHealth(Status status, String message) {

    public enum Status {
        READY,
        DEPENDENCY_UNAVAILABLE,
        UNSUPPORTED_VERSION,
        INCOMPATIBLE,
        MISCONFIGURED
    }

    public boolean ready() {
        return status == Status.READY;
    }

    public static AdapterHealth ok() {
        return new AdapterHealth(Status.READY, "OK");
    }

    public static AdapterHealth unavailable(String message) {
        return new AdapterHealth(Status.DEPENDENCY_UNAVAILABLE, message);
    }
}

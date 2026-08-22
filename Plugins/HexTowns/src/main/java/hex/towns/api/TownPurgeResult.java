package hex.towns.api;

public record TownPurgeResult(
        String namespace,
        boolean success,
        int deletedRows,
        String error
) {
    public static TownPurgeResult ok(String namespace) {
        return new TownPurgeResult(namespace, true, -1, null);
    }

    public static TownPurgeResult failed(String namespace, Throwable throwable) {
        String message = throwable == null ? "unknown error" : rootMessage(throwable);
        return new TownPurgeResult(namespace, false, -1, message);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}

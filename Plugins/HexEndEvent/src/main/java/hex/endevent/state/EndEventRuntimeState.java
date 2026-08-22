package hex.endevent.state;

public final class EndEventRuntimeState {
    public static final int SCHEMA_VERSION = 1;

    private String preparedEventId = "";
    private String activeEventId = "";
    private String activeUntil = "";
    private String lastFinishedEventId = "";
    private boolean resetRequired = true;
    private String generationEventId = "";
    private long generationSeed = 0L;

    public String preparedEventId() { return preparedEventId; }
    public void preparedEventId(String value) { preparedEventId = safe(value); }
    public String activeEventId() { return activeEventId; }
    public void activeEventId(String value) { activeEventId = safe(value); }
    public String activeUntil() { return activeUntil; }
    public void activeUntil(String value) { activeUntil = safe(value); }
    public String lastFinishedEventId() { return lastFinishedEventId; }
    public void lastFinishedEventId(String value) { lastFinishedEventId = safe(value); }
    public boolean resetRequired() { return resetRequired; }
    public void resetRequired(boolean value) { resetRequired = value; }
    public String generationEventId() { return generationEventId; }
    public void generationEventId(String value) { generationEventId = safe(value); }
    public long generationSeed() { return generationSeed; }
    public void generationSeed(long value) { generationSeed = value; }

    private static String safe(String value) { return value == null ? "" : value; }
}

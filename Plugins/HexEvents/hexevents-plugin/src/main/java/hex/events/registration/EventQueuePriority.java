package hex.events.registration;

public enum EventQueuePriority {
    MEDIA(400, "MEDIA", "nte.media"),
    ELITA(300, "ELITA", "nte.elita"),
    SVIP(200, "SVIP", "nte.svip"),
    VIP(100, "VIP", "nte.vip"),
    NORMAL(0, "ZWYKŁY", "");

    private final int weight;
    private final String displayName;
    private final String permission;

    EventQueuePriority(int weight, String displayName, String permission) {
        this.weight = weight;
        this.displayName = displayName;
        this.permission = permission;
    }

    public int weight() { return weight; }
    public String displayName() { return displayName; }
    public String permission() { return permission; }
}

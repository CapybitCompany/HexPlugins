package hex.towns.model;

public enum TownRole {
    OWNER(0),
    COOP(1);

    private final int id;

    TownRole(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static TownRole fromId(int id) {
        return id == 0 ? OWNER : COOP;
    }
}
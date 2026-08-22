package hex.minions.energy;

public enum CableType {
    COPPER, GOLD, GLASS;
    public static CableType fromSpecialItem(String id) {
        if (id == null) return null;
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "copper_cable" -> COPPER;
            case "gold_cable" -> GOLD;
            case "glass_cable" -> GLASS;
            default -> null;
        };
    }
}

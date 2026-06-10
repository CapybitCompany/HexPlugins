package hexnpc.model;

public record NpcSkin(
        String name,
        String value,
        String signature
) {
    public NpcSkin {
        name = trimToNull(name);
        value = trimToNull(value);
        signature = trimToNull(signature);
    }

    public static NpcSkin ofName(String playerName) {
        return new NpcSkin(playerName, null, null);
    }

    public static NpcSkin ofTexture(String value, String signature) {
        return new NpcSkin(null, value, signature);
    }

    public boolean hasTexture() {
        return value != null && !value.isEmpty();
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}

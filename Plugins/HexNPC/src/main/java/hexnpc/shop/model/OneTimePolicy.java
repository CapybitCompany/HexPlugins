package hexnpc.shop.model;

/**
 * Jednorazowy zakup oparty na zewnętrznym ownership permission.
 * Permission jest autorytatywnym źródłem prawdy przed i po transakcji.
 */
public record OneTimePolicy(boolean enabled, String permission) {
    public OneTimePolicy {
        permission = permission == null ? "" : permission.trim();
        if (enabled && permission.isEmpty()) {
            throw new IllegalArgumentException("one-time.enabled=true requires permission");
        }
    }

    public static OneTimePolicy disabled() {
        return new OneTimePolicy(false, "");
    }
}

package hexpvpsmp.config;

/**
 * All player- and admin-facing message templates. Loaded from the {@code messages:}
 * config section so the UI language (Polish by default) is fully configurable.
 * Placeholders:
 * <ul>
 *   <li>{@code commandBlocked} -&gt; {@code <command>}</li>
 *   <li>{@code combatActionbar} -&gt; {@code <seconds>}</li>
 * </ul>
 */
public record MessagesConfig(
        String noPermission,
        String pvpDenied,
        String safezoneEntryDenied,
        String commandBlocked,
        String combatActionbar,
        String leavingSpawn,
        String buildDenied,
        String reloadSuccess,
        String reloadFailed
) {
    public MessagesConfig {
        noPermission = orDefault(noPermission, "&cNie masz uprawnień.");
        pvpDenied = orDefault(pvpDenied, "&cPvP jest tutaj wyłączone.");
        safezoneEntryDenied = orDefault(safezoneEntryDenied, "&cNie możesz wejść na spawn podczas walki.");
        commandBlocked = orDefault(commandBlocked, "&cNie możesz użyć &f/<command> &cpodczas walki.");
        combatActionbar = orDefault(combatActionbar, "&cWalka: &f<seconds>s");
        leavingSpawn = orDefault(leavingSpawn, "&eOpuszczasz ochronę spawnu.");
        buildDenied = orDefault(buildDenied, "&cNie możesz tutaj budować.");
        reloadSuccess = orDefault(reloadSuccess, "&aHexPvpSmp przeładowany.");
        reloadFailed = orDefault(reloadFailed, "&cPrzeładowanie nie powiodło się. Sprawdź konsolę.");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static MessagesConfig defaults() {
        return new MessagesConfig(null, null, null, null, null, null, null, null, null);
    }
}

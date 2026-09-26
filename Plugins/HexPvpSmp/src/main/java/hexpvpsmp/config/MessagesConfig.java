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
        String interactDenied,
        String itemDenied,
        String reloadSuccess,
        String reloadFailed,
        String safezoneEnterTitle,
        String safezoneEnterSubtitle,
        String safezoneExitTitle,
        String safezoneExitSubtitle
) {
    public MessagesConfig {
        noPermission = orDefault(noPermission, "&cNie masz uprawnień.");
        pvpDenied = orDefault(pvpDenied, "&cPvP jest tutaj wyłączone.");
        safezoneEntryDenied = orDefault(safezoneEntryDenied, "&cNie możesz wejść do spawnu podczas walki.");
        commandBlocked = orDefault(commandBlocked, "&cNie możesz użyć &f/<command> &cpodczas walki.");
        combatActionbar = orDefault(combatActionbar, "&cWalka: &f<seconds>s");
        leavingSpawn = orDefault(leavingSpawn, "&eOpuszczasz ochronę spawnu.");
        buildDenied = orDefault(buildDenied, "&cNie możesz tutaj budować.");
        interactDenied = orDefault(interactDenied, "&cNie możesz tego tutaj używać.");
        itemDenied = orDefault(itemDenied, "&cNie możesz używać tego przedmiotu w tej strefie.");
        reloadSuccess = orDefault(reloadSuccess, "&aHexPvpSmp przeładowany.");
        reloadFailed = orDefault(reloadFailed, "&cPrzeładowanie nie powiodło się. Sprawdź konsolę.");
        safezoneEnterTitle = orDefault(safezoneEnterTitle, "&aBezpieczna strefa");
        safezoneEnterSubtitle = orDefault(safezoneEnterSubtitle, "&7PvP jest tutaj wyłączone.");
        safezoneExitTitle = orDefault(safezoneExitTitle, "&cOpuszczasz bezpieczną strefę");
        safezoneExitSubtitle = orDefault(safezoneExitSubtitle, "&7PvP jest teraz aktywne.");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    public static MessagesConfig defaults() {
        return new MessagesConfig(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}

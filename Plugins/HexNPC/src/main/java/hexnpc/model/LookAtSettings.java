package hexnpc.model;

/**
 * Steuert, ob ein NPC nahe Spieler mit Kopf/Koerper anschaut. Bewusst ein eigener
 * Block (nicht Teil von {@link NpcAppearance} oder {@link InteractionSettings}), damit
 * das Verfolgen unabhaengig von Dialogue/Proximity-Triggern konfiguriert werden kann —
 * analog zu den anderen eigenstaendigen Settings-Records auf {@link NpcDefinition}.
 *
 * <ul>
 *   <li>{@code enabled} — ob der NPC nahe Spieler anschaut.</li>
 *   <li>{@code range} — Sichtweite in Bloecken; {@code <= 0} bedeutet "Fallback"
 *       (siehe {@link #effectiveRange(double)}).</li>
 *   <li>{@code intervalTicks} — Update-Intervall des Tracking-Ticks, immer {@code >= 1}.</li>
 *   <li>{@code resetWhenEmpty} — wenn {@code true}, dreht der NPC zurueck auf seine
 *       gespeicherte Rotation, sobald kein Spieler mehr in Range ist.</li>
 * </ul>
 */
public record LookAtSettings(
        boolean enabled,
        double range,
        int intervalTicks,
        boolean resetWhenEmpty
) {
    public static final int DEFAULT_INTERVAL_TICKS = 5;

    public LookAtSettings {
        range = Math.max(0.0D, range);
        // 0 (nicht gesetzt) -> Default; danach hart auf >= 1 clampen.
        intervalTicks = Math.max(1, intervalTicks <= 0 ? DEFAULT_INTERVAL_TICKS : intervalTicks);
    }

    public static LookAtSettings defaults() {
        return new LookAtSettings(false, 0.0D, DEFAULT_INTERVAL_TICKS, true);
    }

    public boolean hasRange() {
        return range > 0.0D;
    }

    /** Konfigurierte Range oder — falls {@code <= 0} — der uebergebene Fallback. */
    public double effectiveRange(double fallback) {
        return range > 0.0D ? range : fallback;
    }

    public LookAtSettings withEnabled(boolean newEnabled) {
        return new LookAtSettings(newEnabled, range, intervalTicks, resetWhenEmpty);
    }

    public LookAtSettings withRange(double newRange) {
        return new LookAtSettings(enabled, newRange, intervalTicks, resetWhenEmpty);
    }

    public LookAtSettings withIntervalTicks(int newIntervalTicks) {
        return new LookAtSettings(enabled, range, newIntervalTicks, resetWhenEmpty);
    }

    public LookAtSettings withResetWhenEmpty(boolean newReset) {
        return new LookAtSettings(enabled, range, intervalTicks, newReset);
    }
}

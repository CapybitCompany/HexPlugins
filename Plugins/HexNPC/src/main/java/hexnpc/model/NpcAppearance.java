package hexnpc.model;

/**
 * Sichtbare Darstellung eines NPCs, unabhaengig von {@link NpcSkin} (Skin-Quelle)
 * und {@link NpcLocation}.
 *
 * <ul>
 *   <li>{@code displayName} — der sichtbare Nickname (unterstuetzt Legacy-Farben
 *       wie {@code &6} und {@code &l}). {@code null} bedeutet "keinen expliziten
 *       Namen gesetzt" — der Renderer faellt dann auf die NPC-Id zurueck.</li>
 *   <li>{@code glow} — ob der NPC leuchtet (Glowing-Effekt).</li>
 *   <li>{@code pose} — Sitz-/Animationshaltung, nie {@code null}.</li>
 * </ul>
 *
 * <p>Bewusst getrennt von {@link NpcSkin}: ein Skin-Wechsel darf den sichtbaren
 * Nickname nicht veraendern.
 */
public record NpcAppearance(
        String displayName,
        boolean glow,
        NpcPose pose
) {
    public NpcAppearance {
        displayName = trimToNull(displayName);
        pose = pose == null ? NpcPose.STANDING : pose;
    }

    public static NpcAppearance defaults() {
        return new NpcAppearance(null, false, NpcPose.STANDING);
    }

    public boolean hasDisplayName() {
        return displayName != null;
    }

    public NpcAppearance withDisplayName(String newDisplayName) {
        return new NpcAppearance(newDisplayName, glow, pose);
    }

    public NpcAppearance withGlow(boolean newGlow) {
        return new NpcAppearance(displayName, newGlow, pose);
    }

    public NpcAppearance withPose(NpcPose newPose) {
        return new NpcAppearance(displayName, glow, newPose);
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}

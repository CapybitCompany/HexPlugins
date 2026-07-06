package hexnpc.model;

import java.util.Locale;

/**
 * Sichtbare Darstellung eines NPCs, unabhaengig von {@link NpcSkin} (Skin-Quelle)
 * und {@link NpcLocation}.
 *
 * <ul>
 *   <li>{@code displayName} — der sichtbare Nickname (unterstuetzt Legacy-Farben
 *       wie {@code &6} und {@code &l}). {@code null} bedeutet "keinen expliziten
 *       Namen gesetzt" — der Renderer faellt dann auf die NPC-Id zurueck.</li>
 *   <li>{@code glow} — ob der NPC leuchtet (Glowing-Effekt).</li>
 *   <li>{@code glowColor} — optionale Farbe des Leuchtens (Minecraft-Team-Farbname
 *       wie {@code gold}/{@code red}/{@code blue}). {@code null} = Standard (weiss).
 *       Wirkt nur zusammen mit {@code glow}; beeinflusst NICHT die aus {@code &}-Codes
 *       geparste Nameplate-Farbe.</li>
 *   <li>{@code pose} — Sitz-/Animationshaltung, nie {@code null}.</li>
 * </ul>
 *
 * <p>Bewusst getrennt von {@link NpcSkin}: ein Skin-Wechsel darf den sichtbaren
 * Nickname nicht veraendern.
 */
public record NpcAppearance(
        String displayName,
        boolean glow,
        String glowColor,
        NpcPose pose
) {
    public NpcAppearance {
        displayName = trimToNull(displayName);
        glowColor = normalizeColor(glowColor);
        pose = pose == null ? NpcPose.STANDING : pose;
    }

    /**
     * Rueckwaerts-kompatibler Konstruktor ohne Glow-Farbe (Default: keine Farbe).
     * Bestehende Aufrufer und Tests kompilieren damit unveraendert weiter.
     */
    public NpcAppearance(String displayName, boolean glow, NpcPose pose) {
        this(displayName, glow, null, pose);
    }

    public static NpcAppearance defaults() {
        return new NpcAppearance(null, false, null, NpcPose.STANDING);
    }

    public boolean hasDisplayName() {
        return displayName != null;
    }

    public boolean hasGlowColor() {
        return glowColor != null;
    }

    public NpcAppearance withDisplayName(String newDisplayName) {
        return new NpcAppearance(newDisplayName, glow, glowColor, pose);
    }

    public NpcAppearance withGlow(boolean newGlow) {
        return new NpcAppearance(displayName, newGlow, glowColor, pose);
    }

    public NpcAppearance withGlowColor(String newGlowColor) {
        return new NpcAppearance(displayName, glow, newGlowColor, pose);
    }

    public NpcAppearance withPose(NpcPose newPose) {
        return new NpcAppearance(displayName, glow, glowColor, newPose);
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeColor(String v) {
        String t = trimToNull(v);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }
}

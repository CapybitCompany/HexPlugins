package hexnpc.model;

/**
 * Skin-Quelle eines NPCs. Minecraft benoetigt am Ende immer eine signierte
 * Textures-Property ({@code value} + {@code signature}) — eine nackte PNG-URL
 * reicht clientseitig nicht. Deshalb kennt dieser Record mehrere Quellen, die
 * alle zu {@code value}/{@code signature} aufgeloest werden:
 *
 * <ul>
 *   <li>{@code name} — Mojang-Username; ueber die Session-API zu Textures aufgeloest.</li>
 *   <li>{@code value}/{@code signature} — bereits signierte Textures (Direktkonfiguration
 *       oder Ergebnis einer Aufloesung; short-circuit, keine API noetig).</li>
 *   <li>{@code url} — Skin-PNG-URL; ueber MineSkin v2 zu signierten Textures aufgeloest.</li>
 *   <li>{@code mineskinUuid} — vorhandene MineSkin-Skin-UUID; ueber {@code /v2/skins/:uuid}
 *       aufgeloest.</li>
 * </ul>
 *
 * <p>Bewusst getrennt von {@link NpcAppearance}: ein Skin-Wechsel darf weder den
 * sichtbaren Nickname noch den technischen Profilnamen veraendern.
 */
public record NpcSkin(
        String name,
        String value,
        String signature,
        String url,
        String mineskinUuid
) {
    public NpcSkin {
        name = trimToNull(name);
        value = trimToNull(value);
        signature = trimToNull(signature);
        url = trimToNull(url);
        mineskinUuid = trimToNull(mineskinUuid);
    }

    /**
     * Rueckwaerts-kompatibler 3-Argument-Konstruktor (name/value/signature) fuer
     * bestehende Aufrufer und Tests — URL/MineSkin-Quellen bleiben leer.
     */
    public NpcSkin(String name, String value, String signature) {
        this(name, value, signature, null, null);
    }

    public static NpcSkin ofName(String playerName) {
        return new NpcSkin(playerName, null, null, null, null);
    }

    public static NpcSkin ofTexture(String value, String signature) {
        return new NpcSkin(null, value, signature, null, null);
    }

    public static NpcSkin ofUrl(String url) {
        return new NpcSkin(null, null, null, url, null);
    }

    public static NpcSkin ofMineSkinUuid(String mineskinUuid) {
        return new NpcSkin(null, null, null, null, mineskinUuid);
    }

    /**
     * True nur bei VOLLSTAENDIGEN, signierten Textures — {@code value} UND
     * {@code signature} muessen vorhanden sein. Sonst wuerden halbe Skin-Daten
     * (z.B. value ohne signature) faelschlich als gueltig behandelt und der Client
     * lehnt den unsignierten Eintrag ab.
     */
    public boolean hasTexture() {
        return value != null && !value.isEmpty()
                && signature != null && !signature.isEmpty();
    }

    public boolean hasUrl() {
        return url != null;
    }

    public boolean hasMineSkinUuid() {
        return mineskinUuid != null;
    }

    public boolean hasName() {
        return name != null;
    }

    /**
     * Kopie mit aufgeloesten, signierten Textures. Quelle (name/url/mineskinUuid)
     * bleibt zur Nachvollziehbarkeit erhalten; da {@link #hasTexture()} nun greift,
     * wird die API bei Spawn/Restart nicht erneut belastet.
     */
    public NpcSkin withTexture(String newValue, String newSignature) {
        return new NpcSkin(name, newValue, newSignature, url, mineskinUuid);
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}

package hexnpc.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Pose/Animation eines NPCs, die der Renderer per Entity-Metadata (bzw. beim
 * Sitzen per Fahrzeug-Mount) an die Clients schickt.
 *
 * <p>Alle Werte werden ausschliesslich ueber stabile Basis-Entity-Metadata
 * umgesetzt (Flags-Byte index 0 und Pose index 6), damit wir keine
 * versionssensiblen Indizes raten muessen — vgl. {@code PlayerSkinLayersMetadata}.
 */
public enum NpcPose {
    /** Standardhaltung (kein Metadata-Override noetig). */
    STANDING,
    /** Sitzt — realisiert ueber ein unsichtbares Reit-Entity (Mount). */
    SITTING,
    /** Liegt flach (Pose SLEEPING). */
    SLEEPING,
    /** Robbt am Boden (Pose SWIMMING). */
    CRAWLING,
    /** Geduckt (Pose CROUCHING + Sneak-Flag). */
    SNEAKING;

    /** Schluessel wie er in npcs.yml persistiert wird. */
    public String storageKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Tolerantes Parsen inkl. Aliassen; unbekannt -> {@link #STANDING}. */
    public static NpcPose fromStorage(String raw) {
        return parse(raw).orElse(STANDING);
    }

    public static Optional<NpcPose> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "standing", "stand", "default", "none" -> Optional.of(STANDING);
            case "sitting", "sit" -> Optional.of(SITTING);
            case "sleeping", "sleep", "lying" -> Optional.of(SLEEPING);
            case "crawling", "crawl", "swimming", "swim" -> Optional.of(CRAWLING);
            case "sneaking", "sneak", "crouching", "crouch" -> Optional.of(SNEAKING);
            default -> Optional.empty();
        };
    }
}

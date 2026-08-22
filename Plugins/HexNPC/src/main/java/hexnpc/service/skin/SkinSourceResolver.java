package hexnpc.service.skin;

import hexnpc.model.NpcSkin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loest eine {@link NpcSkin}-Quelle zu signierten Textures auf und liefert eine
 * Kopie mit gesetztem {@code value}/{@code signature}. Prioritaet:
 *
 * <ol>
 *   <li>bereits vorhandene Textures ({@code value}+{@code signature}) — short-circuit,
 *       keine API;</li>
 *   <li>{@code url} ueber MineSkin v2 ({@code POST /v2/queue} + Polling);</li>
 *   <li>{@code mineskinUuid} ueber MineSkin v2 ({@code GET /v2/skins/:uuid});</li>
 *   <li>{@code name} ueber die Mojang-Session-API ({@link SkinResolver}).</li>
 * </ol>
 *
 * <p>Jeder Fehlerfall (deaktiviert, HTTP-Fehler, Timeout, Exception) faellt auf den
 * unveraenderten Eingabe-Skin zurueck, sodass der NPC den alten/Default-Skin behaelt.
 * Es wird nie eine Exception nach aussen gereicht.
 */
public final class SkinSourceResolver {

    private final SkinResolver nameResolver;
    private final MineSkinClient mineSkin;
    private final boolean mineSkinEnabled;
    private final Logger logger;

    public SkinSourceResolver(SkinResolver nameResolver,
                              MineSkinClient mineSkin,
                              boolean mineSkinEnabled,
                              Logger logger) {
        this.nameResolver = Objects.requireNonNull(nameResolver, "nameResolver");
        this.mineSkin = mineSkin; // darf null sein, wenn MineSkin deaktiviert ist
        this.mineSkinEnabled = mineSkinEnabled && mineSkin != null;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * True, wenn der Skin noch keine Textures hat, aber eine aufloesbare Quelle
     * (url/mineskin-uuid/name) besitzt — dann lohnt sich ein Warm-up bei Load.
     */
    public boolean needsResolution(NpcSkin skin) {
        return skin != null && !skin.hasTexture()
                && (skin.hasUrl() || skin.hasMineSkinUuid() || skin.hasName());
    }

    public CompletableFuture<NpcSkin> resolve(NpcSkin skin) {
        if (skin == null) {
            return CompletableFuture.completedFuture(NpcSkin.ofName(""));
        }
        if (skin.hasTexture()) {
            return CompletableFuture.completedFuture(skin);
        }
        try {
            if (skin.hasUrl()) {
                if (!mineSkinEnabled) {
                    logger.warning("HexNPC: skin url configured but MineSkin is disabled — keeping default skin.");
                    return CompletableFuture.completedFuture(skin);
                }
                return applyTexture(skin, mineSkin.fromUrl(skin.url()));
            }
            if (skin.hasMineSkinUuid()) {
                if (!mineSkinEnabled) {
                    logger.warning("HexNPC: mineskin-uuid configured but MineSkin is disabled — keeping default skin.");
                    return CompletableFuture.completedFuture(skin);
                }
                return applyTexture(skin, mineSkin.fromUuid(skin.mineskinUuid()));
            }
            if (skin.hasName()) {
                return nameResolver.resolve(skin.name())
                        .thenApply(resolved -> resolved != null && resolved.hasTexture()
                                ? skin.withTexture(resolved.value(), resolved.signature())
                                : skin)
                        .exceptionally(ex -> fallback(skin, ex));
            }
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(fallback(skin, ex));
        }
        return CompletableFuture.completedFuture(skin);
    }

    private CompletableFuture<NpcSkin> applyTexture(NpcSkin skin,
                                                    CompletableFuture<Optional<TextureData>> future) {
        return future
                .thenApply(opt -> opt
                        .filter(TextureData::isComplete)
                        .map(td -> skin.withTexture(td.value(), td.signature()))
                        .orElse(skin))
                .exceptionally(ex -> fallback(skin, ex));
    }

    private NpcSkin fallback(NpcSkin skin, Throwable ex) {
        logger.log(Level.WARNING, "HexNPC: skin resolution failed, keeping default skin: " + ex.getMessage());
        return skin;
    }
}

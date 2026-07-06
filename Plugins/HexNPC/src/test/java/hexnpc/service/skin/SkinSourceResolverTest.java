package hexnpc.service.skin;

import hexnpc.config.HexNpcConfig;
import hexnpc.model.NpcSkin;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prioritaet und Fallback der Skin-Quellen-Aufloesung: direkte Textures, URL/MineSkin,
 * Spielername sowie das Verhalten, wenn MineSkin deaktiviert ist oder die API fehlschlaegt.
 */
class SkinSourceResolverTest {

    private static final Logger LOG = Logger.getLogger(SkinSourceResolverTest.class.getName());
    private static final HexNpcConfig.Skins.MineSkin MS_CONFIG =
            new HexNpcConfig.Skins.MineSkin(true, null, "HexNPC-Test",
                    "https://api.mineskin.org", 5, 3, 100L);

    private SkinResolver nameResolver() {
        return new SkinResolver(LOG);
    }

    private MineSkinClient mineSkin(MineSkinClient.Http http) {
        return new MineSkinClient(MS_CONFIG, LOG, http, Runnable::run);
    }

    private MineSkinClient.Http httpReturning(int status, String body) {
        return new MineSkinClient.Http() {
            @Override
            public CompletableFuture<MineSkinClient.Response> get(String url, Map<String, String> h) {
                return CompletableFuture.completedFuture(new MineSkinClient.Response(status, body));
            }

            @Override
            public CompletableFuture<MineSkinClient.Response> post(String url, String b, Map<String, String> h) {
                return CompletableFuture.completedFuture(new MineSkinClient.Response(status, body));
            }
        };
    }

    @Test
    void directTexturesShortCircuitWithoutApi() {
        SkinSourceResolver resolver = new SkinSourceResolver(nameResolver(), null, false, LOG);
        NpcSkin skin = NpcSkin.ofTexture("VAL", "SIG");
        assertFalse(resolver.needsResolution(skin), "vorhandene Textures brauchen keine Aufloesung");
        NpcSkin resolved = resolver.resolve(skin).join();
        assertEquals("VAL", resolved.value());
        assertEquals("SIG", resolved.signature());
    }

    @Test
    void urlResolvesViaMineSkinAndKeepsSource() {
        String json = "{\"skin\":{\"uuid\":\"u\",\"texture\":{\"data\":{\"value\":\"UV\",\"signature\":\"US\"}}}}";
        SkinSourceResolver resolver = new SkinSourceResolver(
                nameResolver(), mineSkin(httpReturning(200, json)), true, LOG);
        NpcSkin skin = NpcSkin.ofUrl("https://example/skin.png");
        assertTrue(resolver.needsResolution(skin));
        NpcSkin resolved = resolver.resolve(skin).join();
        assertEquals("UV", resolved.value());
        assertEquals("US", resolved.signature());
        assertTrue(resolved.hasTexture());
        assertEquals("https://example/skin.png", resolved.url(), "Quelle bleibt zur Nachvollziehbarkeit erhalten");
    }

    @Test
    void mineSkinUuidResolvesViaSkinsEndpoint() {
        String json = "{\"skin\":{\"uuid\":\"abc\",\"texture\":{\"data\":{\"value\":\"MV\",\"signature\":\"MS\"}}}}";
        SkinSourceResolver resolver = new SkinSourceResolver(
                nameResolver(), mineSkin(httpReturning(200, json)), true, LOG);
        NpcSkin resolved = resolver.resolve(NpcSkin.ofMineSkinUuid("abc")).join();
        assertEquals("MV", resolved.value());
        assertEquals("MS", resolved.signature());
    }

    @Test
    void nameResolvesViaMojangCache() {
        SkinResolver names = nameResolver();
        names.seedCache("Notch", new NpcSkin("Notch", "NV", "NS"));
        SkinSourceResolver resolver = new SkinSourceResolver(names, null, false, LOG);
        NpcSkin resolved = resolver.resolve(NpcSkin.ofName("Notch")).join();
        assertEquals("NV", resolved.value());
        assertEquals("NS", resolved.signature());
        assertEquals("Notch", resolved.name());
    }

    @Test
    void urlKeepsDefaultSkinWhenMineSkinDisabled() {
        SkinSourceResolver resolver = new SkinSourceResolver(nameResolver(), null, false, LOG);
        NpcSkin skin = NpcSkin.ofUrl("https://example/skin.png");
        NpcSkin resolved = resolver.resolve(skin).join();
        assertFalse(resolved.hasTexture(), "deaktiviert -> keine Textures, Default bleibt");
        assertEquals("https://example/skin.png", resolved.url());
    }

    @Test
    void urlKeepsDefaultSkinWhenApiFails() {
        SkinSourceResolver resolver = new SkinSourceResolver(
                nameResolver(), mineSkin(httpReturning(500, "{\"error\":\"boom\"}")), true, LOG);
        NpcSkin resolved = resolver.resolve(NpcSkin.ofUrl("https://example/skin.png")).join();
        assertFalse(resolved.hasTexture(), "API-Fehler -> Default-Skin behalten");
    }

    @Test
    void skinWithoutAnySourceNeedsNoResolution() {
        SkinSourceResolver resolver = new SkinSourceResolver(nameResolver(), null, false, LOG);
        NpcSkin empty = new NpcSkin(null, null, null);
        assertFalse(resolver.needsResolution(empty));
        NpcSkin resolved = resolver.resolve(empty).join();
        assertNull(resolved.value());
    }
}

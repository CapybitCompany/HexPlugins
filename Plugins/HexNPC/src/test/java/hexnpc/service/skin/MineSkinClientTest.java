package hexnpc.service.skin;

import hexnpc.config.HexNpcConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MineSkin-v2-Mapping und Fallback. HTTP wird ueber die {@link MineSkinClient.Http}-Seam
 * gemockt; der Delay-Executor laeuft synchron, damit Polling ohne echte Wartezeit testbar ist.
 */
class MineSkinClientTest {

    private static final Logger LOG = Logger.getLogger(MineSkinClientTest.class.getName());
    private static final HexNpcConfig.Skins.MineSkin CONFIG =
            new HexNpcConfig.Skins.MineSkin(true, "test-key", "HexNPC-Test",
                    "https://api.mineskin.org", 5, 5, 100L);

    /** Fake-Transport: liefert vorkonfigurierte Antworten anhand des URL-Suffix. */
    private static final class FakeHttp implements MineSkinClient.Http {
        private final Map<String, MineSkinClient.Response> gets = new HashMap<>();
        private final Map<String, MineSkinClient.Response> posts = new HashMap<>();
        int getCalls;
        int postCalls;

        FakeHttp onGet(String suffix, int status, String body) {
            gets.put(suffix, new MineSkinClient.Response(status, body));
            return this;
        }

        FakeHttp onPost(String suffix, int status, String body) {
            posts.put(suffix, new MineSkinClient.Response(status, body));
            return this;
        }

        @Override
        public CompletableFuture<MineSkinClient.Response> get(String url, Map<String, String> headers) {
            getCalls++;
            return CompletableFuture.completedFuture(match(gets, url));
        }

        @Override
        public CompletableFuture<MineSkinClient.Response> post(String url, String jsonBody, Map<String, String> headers) {
            postCalls++;
            return CompletableFuture.completedFuture(match(posts, url));
        }

        private MineSkinClient.Response match(Map<String, MineSkinClient.Response> table, String url) {
            for (Map.Entry<String, MineSkinClient.Response> e : table.entrySet()) {
                if (url.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return new MineSkinClient.Response(404, "{}");
        }
    }

    private MineSkinClient client(FakeHttp http) {
        return new MineSkinClient(CONFIG, LOG, http, Runnable::run);
    }

    @Test
    void parseTextureDataExtractsValueAndSignature() {
        String json = "{\"skin\":{\"uuid\":\"u\",\"texture\":{\"data\":{\"value\":\"VAL\",\"signature\":\"SIG\"}}}}";
        Optional<TextureData> td = MineSkinClient.parseTextureData(json);
        assertTrue(td.isPresent());
        assertEquals("VAL", td.get().value());
        assertEquals("SIG", td.get().signature());
        assertTrue(td.get().isComplete());
    }

    @Test
    void parseTextureDataEmptyWhenMissing() {
        assertTrue(MineSkinClient.parseTextureData("{\"job\":{\"id\":\"x\"}}").isEmpty());
        assertTrue(MineSkinClient.parseTextureData(null).isEmpty());
    }

    @Test
    void fromUuidMapsSignedTextures() {
        FakeHttp http = new FakeHttp().onGet("/v2/skins/abc", 200,
                "{\"skin\":{\"uuid\":\"abc\",\"texture\":{\"data\":{\"value\":\"V\",\"signature\":\"S\"}}}}");
        Optional<TextureData> td = client(http).fromUuid("abc").join();
        assertTrue(td.isPresent());
        assertEquals("V", td.get().value());
        assertEquals("S", td.get().signature());
    }

    @Test
    void fromUuidFallsBackOnNon2xx() {
        FakeHttp http = new FakeHttp().onGet("/v2/skins/missing", 404, "{}");
        assertTrue(client(http).fromUuid("missing").join().isEmpty(),
                "Fehlerantwort -> leer, Aufrufer behaelt Default-Skin");
    }

    @Test
    void fromUrlReturnsInlineTextureWhenQueueCompletesImmediately() {
        FakeHttp http = new FakeHttp().onPost("/v2/queue", 200,
                "{\"skin\":{\"uuid\":\"u\",\"texture\":{\"data\":{\"value\":\"IV\",\"signature\":\"IS\"}}}}");
        Optional<TextureData> td = client(http).fromUrl("https://example/skin.png").join();
        assertTrue(td.isPresent());
        assertEquals("IV", td.get().value());
        assertEquals(0, http.getCalls, "keine weitere Anfrage noetig, Textur war inline");
    }

    @Test
    void fromUrlResolvesViaSkinUuidWhenQueueReturnsUuidOnly() {
        FakeHttp http = new FakeHttp()
                .onPost("/v2/queue", 200, "{\"skin\":{\"uuid\":\"abc\"}}")
                .onGet("/v2/skins/abc", 200,
                        "{\"skin\":{\"uuid\":\"abc\",\"texture\":{\"data\":{\"value\":\"UV\",\"signature\":\"US\"}}}}");
        Optional<TextureData> td = client(http).fromUrl("https://example/skin.png").join();
        assertTrue(td.isPresent());
        assertEquals("UV", td.get().value());
    }

    @Test
    void fromUrlPollsJobThenFetchesSkin() {
        FakeHttp http = new FakeHttp()
                .onPost("/v2/queue", 200, "{\"job\":{\"id\":\"job1\",\"status\":\"pending\"}}")
                .onGet("/v2/queue/job1", 200,
                        "{\"job\":{\"id\":\"job1\",\"status\":\"completed\"},\"skin\":{\"uuid\":\"abc\"}}")
                .onGet("/v2/skins/abc", 200,
                        "{\"skin\":{\"uuid\":\"abc\",\"texture\":{\"data\":{\"value\":\"PV\",\"signature\":\"PS\"}}}}");
        Optional<TextureData> td = client(http).fromUrl("https://example/skin.png").join();
        assertTrue(td.isPresent());
        assertEquals("PV", td.get().value());
    }

    @Test
    void fromUrlFallsBackWhenQueueFails() {
        FakeHttp http = new FakeHttp().onPost("/v2/queue", 500, "{\"error\":\"boom\"}");
        assertTrue(client(http).fromUrl("https://example/skin.png").join().isEmpty());
    }

    @Test
    void fromUrlFallsBackWhenJobNeverCompletes() {
        FakeHttp http = new FakeHttp()
                .onPost("/v2/queue", 200, "{\"job\":{\"id\":\"job1\",\"status\":\"pending\"}}")
                .onGet("/v2/queue/job1", 200, "{\"job\":{\"id\":\"job1\",\"status\":\"pending\"}}");
        assertFalse(client(http).fromUrl("https://example/skin.png").join().isPresent(),
                "erschoepfte Poll-Versuche -> leer");
    }
}

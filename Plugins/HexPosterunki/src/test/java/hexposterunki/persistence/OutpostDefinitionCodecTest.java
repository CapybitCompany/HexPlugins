package hexposterunki.persistence;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Review round 2, finding 1: the stored geometry is exactly the geometry the run started with. */
class OutpostDefinitionCodecTest {

    private static OutpostDefinition sample() {
        return new OutpostDefinition("fort_polnocny", "Fort Północny – Żelazna Brama", "world_nether",
                Cuboid.of("world_nether", new BlockVec(-1200, -60, 35), new BlockVec(-1140, 110, 95)),
                new PointDef(-1170.5, 70.25, 64.125, 90.5F, -12.75F),
                new PointDef(-1169.0, 71.0, 65.0, 180.0F, 0.0F),
                Map.of("brama", new PointDef(-1190, 66, 60, 0, 0),
                        "wieża.góra", new PointDef(-1150.3, 74.9, 80.1, 45.0F, 10.0F)),
                Map.of("skrzynia_glowna", new BlockVec(-1170, 67, 66), "skrzynia.wiezy", new BlockVec(-1150, 75, 80)),
                7);
    }

    @Test
    void aDecodedDefinitionEqualsTheEncodedOne() {
        OutpostDefinition original = sample();
        assertEquals(original, OutpostDefinitionCodec.decode(OutpostDefinitionCodec.encode(original)),
                "region, punkty z kątami, nazwy z kropkami i polskie znaki muszą przetrwać zapis");
    }

    @Test
    void anUnreadableOrUnknownDefinitionIsRejectedInsteadOfGuessed() {
        assertThrows(IllegalArgumentException.class, () -> OutpostDefinitionCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> OutpostDefinitionCodec.decode("   "));
        assertThrows(IllegalArgumentException.class, () -> OutpostDefinitionCodec.decode("id: [ niezamknięte"));
        String encoded = OutpostDefinitionCodec.encode(sample());
        assertThrows(IllegalArgumentException.class,
                () -> OutpostDefinitionCodec.decode(encoded.replace("format: 1", "format: 99")));
        assertThrows(IllegalArgumentException.class,
                () -> OutpostDefinitionCodec.decode(encoded.replaceAll("(?m)^center:.*$", "")));
    }
}

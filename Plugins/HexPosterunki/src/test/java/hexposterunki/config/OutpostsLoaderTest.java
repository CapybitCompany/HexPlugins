package hexposterunki.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end parse of {@code outposts.yml}: a broken entry is skipped and reported while the
 * remaining fortresses stay usable.
 */
class OutpostsLoaderTest {

    private static final String YAML = """
            outposts:
              fort_dobry:
                display-name: "Fort Północny"
                world: world
                weight: 10
                region:
                  min: "0,60,0"
                  max: "50,100,50"
                center: "25,65,25"
                boss-spawn: "25,66,25"
                spawn-points:
                  brama: "10,62,10"
                  wieza: "40,80,40"
                loot-containers:
                  skrzynia: "25,66,26"
              fort_zly_swiat:
                display-name: "Fort Bez Świata"
                world: nieistniejacy
                region:
                  min: "0,60,0"
                  max: "50,100,50"
                center: "25,65,25"
                spawn-points:
                  brama: "10,62,10"
              fort_punkt_poza:
                display-name: "Fort Z Błędnym Punktem"
                world: world
                region:
                  min: "0,60,0"
                  max: "50,100,50"
                center: "25,65,25"
                spawn-points:
                  brama: "999,62,10"
              fort_bez_regionu:
                display-name: "Fort Bez Regionu"
                world: world
                center: "25,65,25"
            """;

    private OutpostCatalog load() {
        OutpostsLoader loader = new OutpostsLoader(name -> "world".equals(name),
                world -> new int[]{-64, 319});
        return loader.load(YamlConfiguration.loadConfiguration(new StringReader(YAML)));
    }

    @Test
    void onlyTheValidOutpostIsAccepted() {
        OutpostCatalog catalog = load();
        assertEquals(1, catalog.all().size());
        assertTrue(catalog.find("fort_dobry").isPresent());
    }

    @Test
    void everySkippedOutpostCarriesAReason() {
        OutpostCatalog catalog = load();
        assertEquals(3, catalog.report().skipped().size());
        catalog.report().skipped().values().forEach(reason ->
                assertTrue(reason != null && !reason.isBlank(), "powód pominięcia musi być czytelny"));
        assertTrue(catalog.report().skipped().get("fort_zly_swiat").contains("nie istnieje"));
        assertTrue(catalog.report().skipped().get("fort_punkt_poza").contains("brama"));
        assertTrue(catalog.report().skipped().get("fort_bez_regionu").contains("region"));
    }

    @Test
    void theAcceptedOutpostKeepsEveryConfiguredDetail() {
        OutpostDefinition outpost = load().find("fort_dobry").orElseThrow();
        assertEquals("Fort Północny", outpost.displayName());
        assertEquals(10, outpost.weight());
        assertEquals(2, outpost.spawnPoints().size());
        assertEquals(1, outpost.lootContainers().size());
        assertTrue(outpost.region().contains(outpost.bossSpawn()));
    }

    @Test
    void anEmptyFileYieldsNoOutpostsAndAWarning() {
        OutpostsLoader loader = new OutpostsLoader(name -> true, world -> new int[]{-64, 319});
        OutpostCatalog catalog = loader.load(YamlConfiguration.loadConfiguration(new StringReader("")));
        assertTrue(catalog.isEmpty());
        assertTrue(catalog.report().hasValidOutposts() == false);
        assertEquals(1, catalog.report().warnings().size());
    }
}

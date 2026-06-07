package hex.vishopbroadcast.text;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceholderRendererTest {
    @Test
    void replacesKnownPlaceholdersAndKeepsUnknownOnes() {
        String rendered = PlaceholderRenderer.render("{player} kupił {service}{amount_part}{missing}", Map.of(
                "player", "HaViX",
                "service", "Elita",
                "amount_part", ""
        ));

        assertEquals("HaViX kupił Elita{missing}", rendered);
    }

    @Test
    void nullValuesRenderAsEmptyText() {
        String rendered = PlaceholderRenderer.render("A{value}B", new java.util.HashMap<>() {{ put("value", null); }});

        assertEquals("AB", rendered);
    }
}


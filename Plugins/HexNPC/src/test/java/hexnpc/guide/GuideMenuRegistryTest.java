package hexnpc.guide;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMenuRegistryTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) MockBukkit.unmock();
    }

    @Test
    void loadsBundledServerAndArcadeHierarchy(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("guide-menus.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("guide-menus.yml")) {
            if (in == null) throw new IllegalStateException("missing guide-menus.yml test resource");
            Files.copy(in, file);
        }
        GuideMenuRegistry registry = new GuideMenuRegistry(file.toFile(), Logger.getLogger("guide-test"));
        assertEquals(8, registry.reload());
        assertTrue(registry.validationErrors().isEmpty(), registry.validationErrors().toString());
        assertEquals(4, registry.find("server").orElseThrow().entries().size());
        assertEquals(2, registry.find("arcade").orElseThrow().entries().size());
        assertEquals("server", registry.find("server_economy").orElseThrow().parent());
        assertTrue(registry.find("server_economy").orElseThrow().entries().values().stream()
                .anyMatch(entry -> entry.icon().name().contains("HexCoins")));
    }
}

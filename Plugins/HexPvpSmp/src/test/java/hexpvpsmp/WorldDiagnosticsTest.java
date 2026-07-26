package hexpvpsmp;

import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.protection.WorldDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configuration/world consistency diagnostics (wrong world-name detection). */
class WorldDiagnosticsTest {

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private static Map<String, WorldConfig> configuredWorlds(String name, boolean enabled) {
        Map<String, WorldConfig> map = new LinkedHashMap<>();
        WorldConfig world = new WorldConfig(name, enabled, null, null, null);
        map.put(world.world(), world);
        return map;
    }

    // ---- Pure function ---------------------------------------------------

    @Test
    void loadedWorldProducesNoWarning() {
        List<String> warnings = WorldDiagnostics.findConfiguredWorldsNotLoaded(
                configuredWorlds("world", true), List.of("world"));
        assertTrue(warnings.isEmpty(), "a configured world that is loaded must not warn");
    }

    @Test
    void matchIsCaseInsensitive() {
        List<String> warnings = WorldDiagnostics.findConfiguredWorldsNotLoaded(
                configuredWorlds("world", true), List.of("World"));
        assertTrue(warnings.isEmpty(), "world-name comparison must be case-insensitive");
    }

    @Test
    void missingWorldWarnsWithNames() {
        List<String> warnings = WorldDiagnostics.findConfiguredWorldsNotLoaded(
                configuredWorlds("world", true), List.of("smp"));
        assertEquals(1, warnings.size(), "a configured but unloaded world must produce exactly one warning");
        String warning = warnings.get(0);
        assertTrue(warning.contains("'world'"), "warning must name the misconfigured world: " + warning);
        assertTrue(warning.contains("smp"), "warning must list the loaded worlds: " + warning);
    }

    @Test
    void disabledWorldIsIgnored() {
        List<String> warnings = WorldDiagnostics.findConfiguredWorldsNotLoaded(
                configuredWorlds("world", false), List.of("smp"));
        assertTrue(warnings.isEmpty(), "a disabled configured world must not warn");
    }

    @Test
    void emptyConfigProducesNoWarning() {
        assertTrue(WorldDiagnostics.findConfiguredWorldsNotLoaded(Map.of(), List.of("world")).isEmpty());
    }

    // ---- Plugin integration (warning reaches the console logger) ---------

    @Test
    void pluginWarnsOnReloadForUnloadedConfiguredWorld() {
        ServerMock server = MockBukkit.mock();
        server.addSimpleWorld("world");
        HexPvpSmpPlugin plugin = MockBukkit.load(HexPvpSmpPlugin.class);

        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(handler);

        // Configure a world that is NOT loaded on this server.
        plugin.getConfig().set("worlds.ghostworld.enabled", true);
        plugin.getConfig().set("worlds.ghostworld.spawn.enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        boolean warned = records.stream()
                .anyMatch(r -> r.getLevel() == Level.WARNING
                        && r.getMessage() != null
                        && r.getMessage().contains("ghostworld"));
        assertTrue(warned, "reload must warn that the configured world 'ghostworld' is not loaded");

        plugin.getLogger().removeHandler(handler);
    }
}

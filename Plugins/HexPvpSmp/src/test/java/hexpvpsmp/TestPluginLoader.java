package hexpvpsmp;

import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Stable test regions independent of the live server's spawn coordinates. */
public final class TestPluginLoader {
    public static HexPvpSmpPlugin load() {
        HexPvpSmpPlugin plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        try (var input = TestPluginLoader.class.getResourceAsStream("/protection-fixture.yml")) {
            Files.copy(input, plugin.getDataFolder().toPath().resolve("config.yml"), StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        if (!plugin.reloadPluginRuntime()) throw new IllegalStateException("Fixture reload failed");
        return plugin;
    }
}

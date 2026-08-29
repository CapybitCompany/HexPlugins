package hex.limbo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the brand. Every string a player can see has to say "Hex"; the old "HexagonMC" must not
 * come back through a copy-paste, a stale default or a half-finished rename.
 *
 * <p>Only {@code legacy/*-v1.yml} is exempt: those files are frozen snapshots of what an older
 * release shipped, kept purely so {@link ConfigMigrator} can recognise an untouched old default.
 * They are never shown to anybody.
 */
class BrandingTest {

    private static final String OLD_BRAND = "hexagonmc";

    private ConfigLoader loader(Path dir) {
        return new ConfigLoader(dir, LoggerFactory.getLogger(BrandingTest.class));
    }

    @Test
    void noBundledPlayerFacingMessageMentionsTheOldBrand(@TempDir Path tempDir) throws IOException {
        Map<String, String> messages = loader(tempDir).loadMessages().asMap();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).contains(OLD_BRAND)) {
                offenders.add(e.getKey() + " = " + e.getValue());
            }
        }
        assertTrue(offenders.isEmpty(), "messages.yml still mentions the old brand: " + offenders);
    }

    @Test
    void noBundledConfigValueMentionsTheOldBrand(@TempDir Path tempDir) throws IOException {
        loader(tempDir).loadConfig();
        Map<String, String> config = ConfigMigrator.flattenFile(tempDir.resolve("config.yml"));

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : config.entrySet()) {
            if (e.getValue().toLowerCase(Locale.ROOT).contains(OLD_BRAND)) {
                offenders.add(e.getKey() + " = " + e.getValue());
            }
        }
        assertTrue(offenders.isEmpty(), "config.yml still mentions the old brand: " + offenders);
    }

    @Test
    void noSourceFileOutsideTheFrozenLegacySnapshotsMentionsTheOldBrand() throws IOException {
        Path module = moduleRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(module.resolve("src"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.toString();
                if (!name.endsWith(".java") && !name.endsWith(".yml")) {
                    continue;
                }
                if (name.contains("/legacy/") || name.endsWith("BrandingTest.java")
                        || name.endsWith("ConfigMigrator.java") || name.endsWith("ConfigMigrationTest.java")) {
                    // The migrator and its tests have to name the old brand in order to remove it.
                    continue;
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.toLowerCase(Locale.ROOT).contains(OLD_BRAND)) {
                    offenders.add(module.relativize(file).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "the old brand survives in: " + offenders);
    }

    @Test
    void theBundledBrandStringsAreTheAgreedOnes(@TempDir Path tempDir) throws IOException {
        MessagesConfig messages = loader(tempDir).loadMessages();

        assertEquals("&6&lHEX", messages.raw("prompts.login.title"), "the big limbo title");
        assertEquals("&6&lHEX", messages.raw("prompts.register.title"), "the big limbo title");
        assertEquals("&7Witamy na &6Hex&7!", messages.raw("prompts.success.subtitle"), "the welcome subtitle");
        assertTrue(messages.raw("prompts.login.bossbar").startsWith("&6Hex &8"),
                "the BossBar must lead with the gold brand prefix");
        assertTrue(messages.raw("prompts.register.bossbar").startsWith("&6Hex &8"),
                "the BossBar must lead with the gold brand prefix");
    }

    /** Walks up from the working directory to the HexLimbo module root. */
    private static Path moduleRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("src/main/java/hex/limbo"))) {
            Path candidate = dir.resolve("Plugins/HexLimbo");
            if (Files.isDirectory(candidate.resolve("src/main/java/hex/limbo"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return dir;
    }
}

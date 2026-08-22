package hexcasino.machine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads the frozen reel pool from the plugin JAR. No runtime generation is permitted. */
public final class DeterministicReelSetRepository {
    public static final int STRIP_LENGTH = 86;
    public static final int PHYSICAL_REELS = 5;
    private static final Map<String, Integer> EXPECTED_COUNTS = Map.of(
            "flint", 22,
            "melon_slice", 18,
            "gold_nugget", 15,
            "blaze_powder", 11,
            "amethyst_shard", 9,
            "emerald", 6,
            "diamond", 3,
            "nether_star", 2
    );

    private final String version;
    private final String sha256;
    private final List<DeterministicReelSet> sets;

    private DeterministicReelSetRepository(String version, String sha256, List<DeterministicReelSet> sets) {
        this.version = version;
        this.sha256 = sha256;
        this.sets = List.copyOf(sets);
    }

    public static DeterministicReelSetRepository load(JavaPlugin plugin, SkillSlotSettings settings) {
        Objects.requireNonNull(plugin, "plugin");
        try (InputStream raw = plugin.getResource("reel_sets.yml")) {
            if (raw == null) throw new IllegalStateException("Missing bundled reel_sets.yml");
            byte[] bytes = raw.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            try (InputStream expectedRaw = plugin.getResource("reel_sets.sha256")) {
                if (expectedRaw == null) throw new IllegalStateException("Missing bundled reel_sets.sha256");
                String expectedLine = new String(expectedRaw.readAllBytes(), StandardCharsets.UTF_8).trim();
                String expectedSha = expectedLine.split("\\s+", 2)[0];
                if (!sha.equalsIgnoreCase(expectedSha)) {
                    throw new IllegalStateException("reel_sets.yml SHA-256 mismatch: expected " + expectedSha + ", got " + sha);
                }
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            String version = yaml.getString("version", "");
            if (!version.equals(settings.reelSetVersion())) {
                throw new IllegalStateException("reel_sets.yml version " + version + " != configured " + settings.reelSetVersion());
            }
            int count = yaml.getInt("reel-set-count", -1);
            if (count != settings.reelSetCount()) {
                throw new IllegalStateException("reel set count " + count + " != configured " + settings.reelSetCount());
            }
            if (yaml.getInt("strip-length", -1) != STRIP_LENGTH) {
                throw new IllegalStateException("reel strip length must be exactly " + STRIP_LENGTH);
            }
            ConfigurationSection root = yaml.getConfigurationSection("sets");
            if (root == null) throw new IllegalStateException("Missing reel_sets.yml sets section");
            List<DeterministicReelSet> sets = new ArrayList<>(count);
            java.util.Set<String> setFingerprints = new java.util.HashSet<>();
            for (int setIndex = 1; setIndex <= count; setIndex++) {
                String prefix = "sets." + setIndex + ".";
                List<ReelStrip> reels = new ArrayList<>(PHYSICAL_REELS);
                StringBuilder fingerprint = new StringBuilder();
                for (int reel = 1; reel <= PHYSICAL_REELS; reel++) {
                    List<String> symbols = yaml.getStringList(prefix + "reels." + reel);
                    validateStrip(setIndex, reel, symbols);
                    reels.add(new ReelStrip("set-" + setIndex + "-reel-" + reel, symbols));
                    fingerprint.append(String.join(",", symbols)).append('|');
                }
                List<Integer> startsList = yaml.getIntegerList(prefix + "start-positions");
                if (startsList.size() != PHYSICAL_REELS) {
                    throw new IllegalStateException("Set " + setIndex + " must define 5 start positions");
                }
                int[] starts = startsList.stream().mapToInt(Integer::intValue).toArray();
                for (int start : starts) {
                    if (start < 0 || start >= STRIP_LENGTH) throw new IllegalStateException("Invalid start position in set " + setIndex);
                }
                String fp = fingerprint.toString();
                if (!setFingerprints.add(fp)) throw new IllegalStateException("Duplicate deterministic reel set: " + setIndex);
                sets.add(new DeterministicReelSet(setIndex, reels, starts));
            }
            return new DeterministicReelSetRepository(version, sha, sets);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load deterministic reel pool: " + ex.getMessage(), ex);
        }
    }

    private static void validateStrip(int setIndex, int reel, List<String> symbols) {
        if (symbols.size() != STRIP_LENGTH) {
            throw new IllegalStateException("Set " + setIndex + " reel " + reel + " has " + symbols.size() + " symbols, expected 86");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String symbol : symbols) counts.merge(symbol, 1, Integer::sum);
        if (!counts.equals(EXPECTED_COUNTS)) {
            throw new IllegalStateException("Set " + setIndex + " reel " + reel + " has invalid symbol counts: " + counts);
        }
    }

    public DeterministicReelSet set(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > sets.size()) throw new IllegalArgumentException("reel set index out of range: " + oneBasedIndex);
        return sets.get(oneBasedIndex - 1);
    }

    public int count() { return sets.size(); }
    public String version() { return version; }
    public String sha256() { return sha256; }
    public List<DeterministicReelSet> all() { return sets; }
}

package hexcasino.machine;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Loads and validates the frozen 100-board BusDriver pool bundled in the JAR. */
public final class BusDriverBoardRepository {
    private final String version;
    private final String sha256;
    private final List<BusDriverBoard> boards;
    private final int stageCount;

    private BusDriverBoardRepository(String version, String sha256, List<BusDriverBoard> boards, int stageCount) {
        this.version = version;
        this.sha256 = sha256;
        this.boards = List.copyOf(boards);
        this.stageCount = stageCount;
    }

    public static BusDriverBoardRepository load(JavaPlugin plugin, BusDriverSettings settings) {
        Objects.requireNonNull(plugin, "plugin");
        try (InputStream raw = plugin.getResource("busdriver_boards.yml")) {
            if (raw == null) throw new IllegalStateException("Missing bundled busdriver_boards.yml");
            byte[] bytes = raw.readAllBytes();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            try (InputStream expectedRaw = plugin.getResource("busdriver_boards.sha256")) {
                if (expectedRaw == null) throw new IllegalStateException("Missing bundled busdriver_boards.sha256");
                String expected = new String(expectedRaw.readAllBytes(), StandardCharsets.UTF_8).trim().split("\\s+", 2)[0];
                if (!sha.equalsIgnoreCase(expected)) throw new IllegalStateException("busdriver_boards.yml SHA-256 mismatch");
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            String version = yaml.getString("version", "");
            if (!version.equals(settings.boardSetVersion())) throw new IllegalStateException("board version " + version + " != " + settings.boardSetVersion());
            int count = yaml.getInt("board-count", -1);
            if (count != settings.requiredBoardCount()) throw new IllegalStateException("board count " + count + " != " + settings.requiredBoardCount());
            ConfigurationSection root = yaml.getConfigurationSection("boards");
            if (root == null) throw new IllegalStateException("Missing boards section");

            BusDriverDeductionEngine engine = new BusDriverDeductionEngine();
            List<BusDriverBoard> boards = new ArrayList<>(count);
            Set<String> fingerprints = new HashSet<>();
            int stagesTotal = 0;
            for (int boardId = 1; boardId <= count; boardId++) {
                ConfigurationSection boardSection = yaml.getConfigurationSection("boards." + boardId);
                if (boardSection == null) throw new IllegalStateException("Missing board " + boardId);
                int boardVersion = boardSection.getInt("version", 1);
                Object stagesRaw = boardSection.get("stages");
                if (!(stagesRaw instanceof java.util.List<?> rawStages) || rawStages.isEmpty()) {
                    throw new IllegalStateException("Board " + boardId + " has no stages");
                }
                List<BusDriverBoard.StageDefinition> stages = new ArrayList<>();
                for (int i = 0; i < rawStages.size(); i++) {
                    if (!(rawStages.get(i) instanceof java.util.Map<?, ?> stageMap)) {
                        throw new IllegalStateException("Board " + boardId + " stage " + (i + 1) + " is not a map");
                    }
                    int stageId = intValue(stageMap.get("id"), i + 1);
                    BusDriverBoard.StageType type = BusDriverBoard.StageType.valueOf(String.valueOf(stageMap.get("type")));
                    String target = String.valueOf(stageMap.get("target")).toUpperCase(java.util.Locale.ROOT);
                    Object hintsRaw = stageMap.get("hints");
                    if (!(hintsRaw instanceof java.util.List<?> rawHints) || rawHints.size() != 3) {
                        throw new IllegalStateException("Board " + boardId + " stage " + stageId + " must have exactly 3 hints");
                    }
                    List<BusDriverBoard.HintDefinition> hints = new ArrayList<>();
                    Set<Integer> slots = new HashSet<>();
                    for (Object hintRaw : rawHints) {
                        if (!(hintRaw instanceof java.util.Map<?, ?> hintMap)) {
                            throw new IllegalStateException("Invalid hint in board " + boardId + " stage " + stageId);
                        }
                        int slot = intValue(hintMap.get("slot"), -1);
                        if (!slots.add(slot)) throw new IllegalStateException("Duplicate hint slot in board " + boardId + " stage " + stageId);
                        BusDriverBoard.HintType hintType = BusDriverBoard.HintType.valueOf(String.valueOf(hintMap.get("type")));
                        String value = String.valueOf(hintMap.get("value"));
                        hints.add(new BusDriverBoard.HintDefinition(slot, hintType, value));
                    }
                    stages.add(new BusDriverBoard.StageDefinition(stageId, type, target, hints));
                }
                BusDriverBoard board = new BusDriverBoard(boardId, boardVersion, stages);
                BusDriverDeductionEngine.Validation validation = engine.validate(board);
                if (!validation.valid()) throw new IllegalStateException(String.join("; ", validation.errors()));
                String fp = stages.toString();
                if (!fingerprints.add(fp)) throw new IllegalStateException("Duplicate BusDriver board definition: " + boardId);
                stagesTotal += stages.size();
                boards.add(board);
            }
            return new BusDriverBoardRepository(version, sha, boards, stagesTotal);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load deterministic BusDriver boards: " + ex.getMessage(), ex);
        }
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        try { return raw == null ? fallback : Integer.parseInt(raw.toString()); }
        catch (NumberFormatException ex) { return fallback; }
    }

    public BusDriverBoard board(int oneBasedIndex) {
        if (oneBasedIndex < 1 || oneBasedIndex > boards.size()) throw new IllegalArgumentException("board index out of range: " + oneBasedIndex);
        return boards.get(oneBasedIndex - 1);
    }

    public List<BusDriverBoard> all() { return boards; }
    public int count() { return boards.size(); }
    public int stageCount() { return stageCount; }
    public String version() { return version; }
    public String sha256() { return sha256; }
}

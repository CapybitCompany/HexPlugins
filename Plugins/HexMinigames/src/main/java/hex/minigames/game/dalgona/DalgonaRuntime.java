package hex.minigames.game.dalgona;

import hex.minigames.model.BlockPosition;
import org.bukkit.Location;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class DalgonaRuntime {
    private final DalgonaConfig config;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private final DalgonaConfig.Pattern pattern;

    public DalgonaRuntime(DalgonaConfig config, Collection<UUID> participants, Random random) {
        this.config = config;
        Random rng = random == null ? new Random() : random;
        this.pattern = config.patterns().get(rng.nextInt(config.patterns().size()));
        assignPlayers(participants, this.pattern);
    }

    DalgonaRuntime(DalgonaConfig config, Collection<UUID> participants, DalgonaConfig.Pattern pattern) {
        this.config = config;
        this.pattern = pattern == null ? config.patterns().get(0) : pattern;
        assignPlayers(participants, this.pattern);
    }

    private void assignPlayers(Collection<UUID> participants, DalgonaConfig.Pattern pattern) {
        int index = 0;
        for (UUID participant : participants) {
            if (index >= config.stations().size()) break;
            DalgonaConfig.Station station = config.stations().get(index++);
            players.put(participant, new PlayerState(station, arenaBlocks(station, pattern)));
        }
    }

    public DalgonaConfig.Pattern pattern() {
        return pattern;
    }

    public DalgonaConfig.Station station(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? null : state.station;
    }

    public Set<BlockPosition> requiredBlocks(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? Set.of() : Set.copyOf(state.required);
    }

    public Set<BlockPosition> boardBlocks(DalgonaConfig.Station station) {
        return boardBlocks(station, pattern);
    }

    public Set<TemplateMapping> templateMapping(DalgonaConfig.Station station) {
        return templateMapping(station, pattern);
    }

    public static Set<TemplateMapping> templateMapping(DalgonaConfig.Station station, DalgonaConfig.Pattern pattern) {
        Set<TemplateMapping> out = new LinkedHashSet<>();
        for (LogicalPixel pixel : logicalPixels(station, pattern)) {
            out.add(new TemplateMapping(pixel, boardBlock(station, pixel), arenaBlock(station, pixel)));
        }
        return out;
    }

    public static Set<BlockPosition> boardBlocks(DalgonaConfig.Station station, DalgonaConfig.Pattern pattern) {
        Set<BlockPosition> out = new LinkedHashSet<>();
        for (LogicalPixel pixel : logicalPixels(station, pattern)) {
            out.add(boardBlock(station, pixel));
        }
        return out;
    }

    public static Set<BlockPosition> arenaBlocks(DalgonaConfig.Station station, DalgonaConfig.Pattern pattern) {
        Set<BlockPosition> out = new LinkedHashSet<>();
        for (LogicalPixel pixel : logicalPixels(station, pattern)) {
            out.add(arenaBlock(station, pixel));
        }
        return out;
    }

    public BreakResult breakBlock(UUID playerId, BlockPosition block) {
        PlayerState state = players.get(playerId);
        if (state == null || state.status != Status.ACTIVE) return BreakResult.ignored();
        if (!containsArena(state.station, block)) return BreakResult.ignored();
        if (!state.required.contains(block)) {
            state.status = Status.ELIMINATED;
            return new BreakResult(BreakOutcome.WRONG, state.completed.size(), false);
        }
        if (!state.completed.add(block)) return BreakResult.ignored();
        if (state.completed.containsAll(state.required)) {
            state.status = Status.FINISHED;
            return new BreakResult(BreakOutcome.COMPLETE, state.completed.size(), true);
        }
        return new BreakResult(BreakOutcome.CORRECT, state.completed.size(), false);
    }

    public boolean canMine(UUID playerId, BlockPosition block) {
        PlayerState state = players.get(playerId);
        return state != null && state.status == Status.ACTIVE && containsArena(state.station, block);
    }

    public boolean finished(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state != null && state.status == Status.FINISHED;
    }

    public boolean eliminated(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state != null && state.status == Status.ELIMINATED;
    }

    public int progress(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? 0 : state.completed.size();
    }

    public boolean insideStation(UUID playerId, Location location) {
        PlayerState state = players.get(playerId);
        if (state == null || location == null || location.getWorld() == null) return false;
        DalgonaConfig.Station station = state.station;
        return location.getWorld().getName().equals(station.arena().worldName())
                && location.getX() >= station.arena().minX()
                && location.getX() <= station.arena().maxX() + 1.0
                && location.getZ() >= station.arena().minZ()
                && location.getZ() <= station.arena().maxZ() + 1.0;
    }

    public static List<LogicalPixel> logicalPixels(DalgonaConfig.Pattern pattern) {
        java.util.ArrayList<LogicalPixel> out = new java.util.ArrayList<>();
        for (int row = 0; row < pattern.grid().size(); row++) {
            String line = pattern.grid().get(row);
            for (int col = 0; col < Math.min(DalgonaConfig.PATTERN_WIDTH, line.length()); col++) {
                char ch = line.charAt(col);
                if (ch == '#' || ch == 'X' || ch == '1' || ch == 'R') out.add(new LogicalPixel(col, row));
            }
        }
        return out;
    }

    public static BlockPosition arenaBlock(DalgonaConfig.Station station, LogicalPixel pixel) {
        int z = station.arena().minZ() + pixel.v();
        return new BlockPosition(station.arena().minX() + pixel.u(), station.arena().minY(), z);
    }

    public static BlockPosition boardBlock(DalgonaConfig.Station station, LogicalPixel pixel) {
        return new BlockPosition(station.board().minX() + pixel.u(), displayRegion(station).maxY() - pixel.v(), station.board().minZ());
    }

    /** Builds one grid at the floor's exact resolution for both rendering and cut validation. */
    public static List<LogicalPixel> logicalPixels(DalgonaConfig.Station station, DalgonaConfig.Pattern pattern) {
        int width = station.arena().maxX() - station.arena().minX() + 1;
        int height = station.arena().maxZ() - station.arena().minZ() + 1;
        Set<LogicalPixel> source = Set.copyOf(logicalPixels(pattern));
        java.util.ArrayList<LogicalPixel> out = new java.util.ArrayList<>();
        for (int v = 0; v < height; v++) {
            int sourceV = height == 1 ? 0 : (int) Math.round(v * (pattern.grid().size() - 1.0) / (height - 1));
            for (int u = 0; u < width; u++) {
                int sourceU = width == 1 ? 0 : (int) Math.round(u * (DalgonaConfig.PATTERN_WIDTH - 1.0) / (width - 1));
                if (source.contains(new LogicalPixel(sourceU, sourceV))) out.add(new LogicalPixel(u, v));
            }
        }
        return out;
    }

    /** The vertical display and horizontal arena always contain the same number of cells. */
    public static hex.minigames.model.CuboidRegion displayRegion(DalgonaConfig.Station station) {
        return new hex.minigames.model.CuboidRegion(station.board().worldName(),
                new BlockPosition(station.board().minX(), station.board().minY(), station.board().minZ()),
                new BlockPosition(station.board().minX() + station.arena().maxX() - station.arena().minX(),
                        station.board().minY() + station.arena().maxZ() - station.arena().minZ(), station.board().minZ()));
    }

    private boolean containsArena(DalgonaConfig.Station station, BlockPosition block) {
        return block.x() >= station.arena().minX()
                && block.x() <= station.arena().maxX()
                && block.y() == station.arena().minY()
                && block.z() >= station.arena().minZ()
                && block.z() <= station.arena().maxZ();
    }

    public enum BreakOutcome {
        IGNORED,
        CORRECT,
        WRONG,
        COMPLETE
    }

    public record BreakResult(BreakOutcome outcome, int progress, boolean complete) {
        static BreakResult ignored() {
            return new BreakResult(BreakOutcome.IGNORED, 0, false);
        }
    }

    public record LogicalPixel(int u, int v) {
    }

    public record TemplateMapping(LogicalPixel pixel, BlockPosition boardBlock, BlockPosition arenaBlock) {
    }

    private enum Status {
        ACTIVE,
        FINISHED,
        ELIMINATED
    }

    private static final class PlayerState {
        private final DalgonaConfig.Station station;
        private final Set<BlockPosition> required;
        private final Set<BlockPosition> completed = new LinkedHashSet<>();
        private Status status = Status.ACTIVE;

        private PlayerState(DalgonaConfig.Station station, Set<BlockPosition> required) {
            this.station = station;
            this.required = Set.copyOf(required);
        }
    }
}

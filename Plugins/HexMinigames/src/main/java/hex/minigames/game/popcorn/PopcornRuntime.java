package hex.minigames.game.popcorn;

import hex.minigames.model.BlockPosition;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class PopcornRuntime {
    private final PopcornConfig config;
    private final Random random;
    private final List<BlockPosition> blocks;
    private final Set<BlockPosition> notStarted;
    private final Map<BlockPosition, PipelineState> pipeline = new LinkedHashMap<>();
    private final Set<BlockPosition> done = new LinkedHashSet<>();
    private long nextBatchTick;

    public PopcornRuntime(PopcornConfig config, Random random) {
        this.config = config;
        this.random = random == null ? new Random() : random;
        this.blocks = List.copyOf(config.platformBlocks());
        this.notStarted = new LinkedHashSet<>(blocks);
    }

    public List<Command> tick(long elapsedTicks) {
        List<Command> commands = new ArrayList<>();
        activate(elapsedTicks);
        for (Map.Entry<BlockPosition, PipelineState> entry : List.copyOf(pipeline.entrySet())) {
            BlockPosition block = entry.getKey();
            PipelineState state = entry.getValue();
            Stage stage = stageAt(elapsedTicks - state.startedTick);
            if (stage != state.stage) {
                state.stage = stage;
                commands.add(new Command(block, material(stage)));
                if (stage == Stage.AIR) {
                    pipeline.remove(block);
                    done.add(block);
                }
            }
        }
        return commands;
    }

    public double activationRateAt(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return config.activationRateStart()
                + (config.activationRateEnd() - config.activationRateStart()) * Math.pow(clamped, config.accelerationCurve());
    }

    public boolean inPipeline(BlockPosition block) {
        return pipeline.containsKey(block) || done.contains(block);
    }

    public int remainingSolidBlocks() {
        return blocks.size() - done.size();
    }

    private void activate(long elapsedTicks) {
        if (notStarted.size() <= config.targetRemainingBlocks()) return;
        if (elapsedTicks < nextBatchTick) return;
        nextBatchTick = elapsedTicks + 10;
        // Reserve enough time for the final batch to pass through all four colors.
        long activationWindow = Math.max(1L, config.roundDurationSeconds() * 20L - config.stageDurationTicks() * 4L);
        double progress = Math.max(0.0, Math.min(1.0, elapsedTicks / (double) activationWindow));
        double start = config.activationRateStart();
        double delta = config.activationRateEnd() - start;
        double exponent = config.accelerationCurve() + 1.0;
        double totalWeight = start + delta / exponent;
        double fraction = totalWeight > 0.0
                ? (start * progress + delta * Math.pow(progress, exponent) / exponent) / totalWeight
                : progress;
        int removable = Math.max(0, blocks.size() - config.targetRemainingBlocks());
        int desired = Math.min(removable, (int) Math.ceil(removable * fraction));
        nextBatchTick = Math.min(nextBatchTick, activationWindow);
        while (blocks.size() - notStarted.size() < desired) {
            BlockPosition selected = randomNotStarted();
            if (selected == null) return;
            notStarted.remove(selected);
            pipeline.put(selected, new PipelineState(elapsedTicks, Stage.WHITE));
        }
    }

    private BlockPosition randomNotStarted() {
        if (notStarted.isEmpty()) return null;
        int target = random.nextInt(notStarted.size());
        int index = 0;
        for (BlockPosition block : notStarted) {
            if (index++ == target) return block;
        }
        return notStarted.iterator().next();
    }

    private Stage stageAt(long ageTicks) {
        int duration = config.stageDurationTicks();
        if (ageTicks < duration) return Stage.WHITE;
        if (ageTicks < duration * 2L) return Stage.YELLOW;
        if (ageTicks < duration * 3L) return Stage.ORANGE;
        if (ageTicks < duration * 4L) return Stage.RED;
        return Stage.AIR;
    }

    private Material material(Stage stage) {
        return switch (stage) {
            case WHITE -> config.whiteMaterial();
            case YELLOW -> config.yellowMaterial();
            case ORANGE -> config.orangeMaterial();
            case RED -> config.redMaterial();
            case AIR -> Material.AIR;
        };
    }

    public enum Stage {
        WHITE,
        YELLOW,
        ORANGE,
        RED,
        AIR
    }

    public record Command(BlockPosition block, Material material) {
    }

    private static final class PipelineState {
        private final long startedTick;
        private Stage stage;

        private PipelineState(long startedTick, Stage stage) {
            this.startedTick = startedTick;
            this.stage = stage;
        }
    }
}

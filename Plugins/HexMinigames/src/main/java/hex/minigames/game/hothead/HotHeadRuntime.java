package hex.minigames.game.hothead;

import hex.minigames.model.BlockPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class HotHeadRuntime {
    private final HotHeadConfig config;
    private final Random random;
    private Cycle activeCycle;
    private long nextCycleTick;
    private long startTick;
    private boolean accelerated;

    public HotHeadRuntime(HotHeadConfig config, Random random) {
        this.config = config;
        this.random = random == null ? new Random() : random;
    }

    public void start(long nowTick) {
        activeCycle = null;
        nextCycleTick = nowTick;
        startTick = nowTick;
        accelerated = false;
    }

    public List<Command> tick(long nowTick, long roundDurationTicks) {
        List<Command> commands = new ArrayList<>();
        if (!accelerated && nowTick - startTick >= 800L) {
            accelerated = true;
            nextCycleTick = nowTick + 40L;
            if (activeCycle != null) {
                for (DirectedWave directed : activeCycle.waves()) {
                    Wave wave = directed.wave();
                    commands.add(new Command(directed.axis(), CommandType.LAMP_OFF, wave.lane.lamps()));
                    for (int index : wave.raisedSegments.keySet()) {
                        commands.add(new Command(directed.axis(), CommandType.SEGMENT_OFF, wave.lane.segments().get(index)));
                    }
                }
                activeCycle = null;
            }
            commands.add(new Command(Axis.AXIS_X, CommandType.ACCELERATION_NOTICE, List.of()));
            return commands;
        }
        if (activeCycle == null) {
            if (nowTick < nextCycleTick) return commands;
            startCycle(nowTick, commands);
            return commands;
        }

        if (nowTick - activeCycle.startedTick() < timing(config.warningTicks())) return commands;
        for (DirectedWave directed : activeCycle.waves()) {
            tickWave(directed.axis(), directed.wave(), nowTick, commands);
        }

        if (activeCycle.waves().stream().allMatch(directed -> directed.wave().finished())) {
            for (DirectedWave directed : activeCycle.waves()) {
                commands.add(new Command(directed.axis(), CommandType.LAMP_OFF, directed.wave().lane.lamps()));
            }
            activeCycle = null;
            nextCycleTick = nowTick + (accelerated ? timing(config.cycleGapTicks())
                    : Math.max(1, (int) Math.round(config.cycleGapTicks() * 0.8)));
        }
        return commands;
    }

    public int axis1LaneCount() {
        return config.axis1().lanes().size();
    }

    public int axis2LaneCount() {
        return config.axis2().lanes().size();
    }

    public boolean axisActive(Axis axis) {
        return activeWaveCount(axis) > 0;
    }

    public boolean axisActive(int axis) {
        return axisActive(axis == 1 ? Axis.AXIS_X : Axis.AXIS_Z);
    }

    public int activeWaveCount(Axis axis) {
        return activeCycle == null ? 0 : (int) activeCycle.waves().stream()
                .filter(directed -> directed.axis() == axis && !directed.wave().finished()).count();
    }

    public int activeWaveCount(int axis) {
        return activeWaveCount(axis == 1 ? Axis.AXIS_X : Axis.AXIS_Z);
    }

    public int currentLaneId(Axis axis) {
        if (activeCycle == null) return 0;
        return wave(axis).lane.id();
    }

    private void startCycle(long nowTick, List<Command> commands) {
        HotHeadConfig.Lane axisXLane = pick(config.axis1());
        HotHeadConfig.Lane axisZLane = pick(config.axis2());
        if (axisXLane == null || axisZLane == null) return;
        List<DirectedWave> waves = new ArrayList<>();
        waves.add(new DirectedWave(Axis.AXIS_X, new Wave(axisXLane)));
        waves.add(new DirectedWave(Axis.AXIS_Z, new Wave(axisZLane)));
        if (!accelerated) {
            Axis extraAxis = random.nextBoolean() ? Axis.AXIS_X : Axis.AXIS_Z;
            HotHeadConfig.AxisConfig axisConfig = extraAxis == Axis.AXIS_X ? config.axis1() : config.axis2();
            int usedLane = extraAxis == Axis.AXIS_X ? axisXLane.id() : axisZLane.id();
            List<HotHeadConfig.Lane> candidates = axisConfig.lanes().stream().filter(lane -> lane.id() != usedLane).toList();
            if (!candidates.isEmpty()) {
                waves.add(new DirectedWave(extraAxis, new Wave(candidates.get(random.nextInt(candidates.size())))));
            }
        }
        activeCycle = new Cycle(nowTick, List.copyOf(waves));
        for (DirectedWave directed : waves) {
            commands.add(new Command(directed.axis(), CommandType.LAMP_ON, directed.wave().lane.lamps()));
        }
    }

    private HotHeadConfig.Lane pick(HotHeadConfig.AxisConfig config) {
        if (config == null || !config.enabled() || config.lanes().isEmpty()) return null;
        return config.lanes().get(random.nextInt(config.lanes().size()));
    }

    private void tickWave(Axis axis, Wave wave, long nowTick, List<Command> commands) {
        if (wave.finished()) return;
        if (wave.nextSegmentIndex >= wave.lane.segments().size()) {
            if (nowTick >= wave.releaseTick) {
                for (int index : wave.raisedSegments.keySet()) {
                    commands.add(new Command(axis, CommandType.SEGMENT_OFF, wave.lane.segments().get(index)));
                }
                wave.raisedSegments.clear();
            }
            return;
        }
        if (nowTick < wave.nextSegmentTick) return;
        List<BlockPosition> segment = wave.lane.segments().get(wave.nextSegmentIndex);
        commands.add(new Command(axis, CommandType.SEGMENT_ON, segment));
        wave.raisedSegments.put(wave.nextSegmentIndex++, nowTick);
        wave.nextSegmentTick = nowTick + timing(config.waveStepDelayTicks());
        if (wave.nextSegmentIndex == wave.lane.segments().size()) wave.releaseTick = nowTick + timing(config.segmentHoldTicks());
    }

    private int timing(int ticks) { return accelerated ? Math.max(1, (ticks + 1) / 2) : ticks; }

    private Wave wave(Axis axis) {
        if (activeCycle == null) return Wave.EMPTY;
        return activeCycle.waves().stream().filter(directed -> directed.axis() == axis)
                .map(DirectedWave::wave).findFirst().orElse(Wave.EMPTY);
    }

    public enum Axis {
        AXIS_X,
        AXIS_Z
    }

    public enum CommandType {
        ACCELERATION_NOTICE,
        LAMP_ON,
        LAMP_OFF,
        SEGMENT_ON,
        SEGMENT_OFF
    }

    public record Command(Axis axis, CommandType type, List<BlockPosition> positions) {
        public Command {
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    private record Cycle(long startedTick, List<DirectedWave> waves) {
    }

    private record DirectedWave(Axis axis, Wave wave) {
    }

    private static final class Wave {
        private static final Wave EMPTY = new Wave(null);
        private final HotHeadConfig.Lane lane;
        private final java.util.Map<Integer, Long> raisedSegments = new java.util.LinkedHashMap<>();
        private int nextSegmentIndex;
        private long nextSegmentTick;
        private long releaseTick;

        private Wave(HotHeadConfig.Lane lane) {
            this.lane = lane;
        }

        private boolean finished() {
            return lane == null || raisedSegments.isEmpty() && nextSegmentIndex >= lane.segments().size();
        }
    }
}

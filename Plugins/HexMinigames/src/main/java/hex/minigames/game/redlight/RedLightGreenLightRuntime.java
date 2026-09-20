package hex.minigames.game.redlight;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;

public final class RedLightGreenLightRuntime {
    private final RedLightGreenLightConfig config;
    private final Random random;
    private Light light = Light.GREEN;
    private long nextSwitchTick;
    private long redStartedTick = Long.MIN_VALUE;
    private final Map<UUID, Long> finishTicks = new LinkedHashMap<>();

    public RedLightGreenLightRuntime(RedLightGreenLightConfig config, Random random) {
        this.config = config;
        this.random = random == null ? new Random() : random;
    }

    public void start(long nowTick) {
        light = Light.GREEN;
        redStartedTick = Long.MIN_VALUE;
        nextSwitchTick = nowTick + randomBetween(config.greenMinTicks(), config.greenMaxTicks());
    }

    public boolean tick(long nowTick) {
        if (nowTick < nextSwitchTick) return false;
        if (light == Light.GREEN) {
            light = Light.RED;
            redStartedTick = nowTick;
            nextSwitchTick = nowTick + randomBetween(config.redMinTicks(), config.redMaxTicks());
        } else {
            light = Light.GREEN;
            redStartedTick = Long.MIN_VALUE;
            nextSwitchTick = nowTick + randomBetween(config.greenMinTicks(), config.greenMaxTicks());
        }
        return true;
    }

    public Light light() {
        return light;
    }

    public void forceRed(long nowTick) {
        light = Light.RED;
        redStartedTick = nowTick;
        nextSwitchTick = nowTick + config.redMinTicks();
    }

    public boolean movementViolation(long nowTick, MovementSample sample) {
        if (light != Light.RED) return false;
        if (nowTick - redStartedTick < config.redGraceTicks()) return false;
        return sample.positionChanged(config.movementEpsilon()) || sample.rotationChanged(config.rotationEpsilon());
    }

    public boolean strictRedActive(long nowTick) {
        return light == Light.RED && nowTick - redStartedTick >= config.redGraceTicks();
    }

    public boolean markFinished(UUID playerId, long tick) {
        if (finishTicks.containsKey(playerId)) return false;
        finishTicks.put(playerId, tick);
        return true;
    }

    public OptionalLong finishTick(UUID playerId) {
        Long tick = finishTicks.get(playerId);
        return tick == null ? OptionalLong.empty() : OptionalLong.of(tick);
    }

    public void clearFinish(UUID playerId) {
        finishTicks.remove(playerId);
    }

    public long ticksUntilSwitch(long nowTick) {
        return Math.max(0L, nextSwitchTick - nowTick);
    }

    public Map<UUID, Integer> pointsByFinishOrder() {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        int[] placement = {1};
        finishTicks.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getValue))
                .forEach(entry -> out.put(entry.getKey(), config.pointsForPlacement(placement[0]++)));
        return out;
    }

    private int randomBetween(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    public enum Light {
        GREEN,
        RED
    }

    public record MovementSample(
            double fromX,
            double fromY,
            double fromZ,
            float fromYaw,
            float fromPitch,
            double toX,
            double toY,
            double toZ,
            float toYaw,
            float toPitch
    ) {
        boolean positionChanged(double epsilon) {
            return Math.abs(toX - fromX) > epsilon
                    || Math.abs(toY - fromY) > epsilon
                    || Math.abs(toZ - fromZ) > epsilon;
        }

        boolean rotationChanged(double epsilon) {
            return Math.abs(deltaAngle(fromYaw, toYaw)) > epsilon
                    || Math.abs(toPitch - fromPitch) > epsilon;
        }

        private static float deltaAngle(float from, float to) {
            float delta = (to - from) % 360.0f;
            if (delta >= 180.0f) delta -= 360.0f;
            if (delta < -180.0f) delta += 360.0f;
            return delta;
        }
    }
}

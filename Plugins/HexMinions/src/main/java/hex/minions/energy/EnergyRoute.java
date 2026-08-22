package hex.minions.energy;

import java.util.List;

public record EnergyRoute(BlockPos consumerPos, List<BlockPos> cablePath, double lossEu, int bottleneckEuPerSecond) {}

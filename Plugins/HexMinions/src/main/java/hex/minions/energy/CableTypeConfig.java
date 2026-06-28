package hex.minions.energy;

import org.bukkit.Material;

public record CableTypeConfig(String displayName, int maxSegmentLength, double lossEuPerMeter, int maxEuPerSecond, Material displayMaterial, double displayThickness, float viewRange) {}

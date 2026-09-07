package hex.parkour.model;

import hex.parkour.config.BarConfig;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ParkourArena(
        String id,
        String displayName,
        BarConfig bossBar,
        CuboidRegion region,
        CuboidRegion portal,
        LocationSpec spawn,
        CuboidRegion startRegion,
        CuboidRegion finishRegion,
        Set<Material> allowedMaterials,
        Set<EntityType> allowedVehicles,
        List<ParkourCheckpoint> checkpoints,
        Map<String, MoneyPoint> moneyPoints
) {
    public ParkourArena {
        checkpoints = checkpoints.stream()
                .sorted(Comparator.comparingInt(ParkourCheckpoint::order))
                .toList();
        moneyPoints = Map.copyOf(moneyPoints);
        allowedMaterials = Set.copyOf(allowedMaterials);
        allowedVehicles = Set.copyOf(allowedVehicles);
    }

    public ParkourCheckpoint checkpointByOrder(int order) {
        for (ParkourCheckpoint checkpoint : checkpoints) {
            if (checkpoint.order() == order) return checkpoint;
        }
        return null;
    }

    public boolean allCheckpointsCollected(int currentOrder) {
        return checkpoints.isEmpty() || currentOrder >= checkpoints.get(checkpoints.size() - 1).order();
    }
}

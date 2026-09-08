package hexbuildbattle.arena;

import org.bukkit.Location;

public record Arena(
        int index,
        CuboidRegion moduleRegion,
        CuboidRegion buildRegion,
        CuboidRegion floorRegion,
        Location ownerSpawn,
        Location judgingCenter
) {

    public String displayName() {
        return "Arena " + index;
    }
}

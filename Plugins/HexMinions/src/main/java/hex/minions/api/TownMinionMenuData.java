package hex.minions.api;

import java.util.List;
import java.util.UUID;

public record TownMinionMenuData(UUID townUuid, String townName, int minionCount, int minionLimit, List<MinionMenuData> minions) {
}


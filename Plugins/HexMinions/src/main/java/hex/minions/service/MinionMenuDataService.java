package hex.minions.service;

import hex.minions.api.MinionMenuData;
import hex.minions.api.TownMinionMenuData;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface MinionMenuDataService {
    TownMinionMenuData townData(Player viewer);

    Optional<MinionMenuData> minionData(Player viewer, UUID minionId);

    Optional<MinionMenuData> minionByIndex(Player viewer, int index);
}


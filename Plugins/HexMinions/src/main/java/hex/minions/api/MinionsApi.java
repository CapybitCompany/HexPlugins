package hex.minions.api;

import hex.minions.service.OperationResult;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MinionsApi {
    Optional<MinionView> findMinion(UUID minionId);

    List<MinionView> minionsOfTown(UUID townUuid);

    int countMinions(UUID townUuid);

    int maxMinions(UUID townUuid);

    boolean canPlace(Player player, Location location, String typeId);

    CompletableFuture<OperationResult> place(Player player, Location location, ItemStack minionItem);

    CompletableFuture<OperationResult> pickup(Player player, UUID minionId);

    CompletableFuture<OperationResult> move(Player player, UUID minionId, Location targetLocation);

    CompletableFuture<OperationResult> upgrade(Player player, UUID minionId);

    TownMinionMenuData menuData(Player viewer);

    Optional<MinionMenuData> menuData(Player viewer, UUID minionId);

    Optional<MinionMenuData> menuDataByIndex(Player viewer, int index);

    Optional<MinionMenuData> selectedMenuData(Player viewer);

    void registerListener(MinionsListener listener);
}


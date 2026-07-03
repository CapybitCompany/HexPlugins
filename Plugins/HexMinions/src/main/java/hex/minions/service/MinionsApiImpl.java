package hex.minions.service;

import hex.minions.api.MinionMenuData;
import hex.minions.api.MinionView;
import hex.minions.api.MinionsApi;
import hex.minions.api.MinionsListener;
import hex.minions.api.TownMinionMenuData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinionsApiImpl implements MinionsApi {
    private final MinionService service;

    public MinionsApiImpl(MinionService service) {
        this.service = service;
    }

    @Override public Optional<MinionView> findMinion(UUID minionId) { return service.findView(minionId); }
    @Override public List<MinionView> minionsOfTown(UUID townUuid) { return service.viewsOfTown(townUuid); }
    @Override public int countMinions(UUID townUuid) { return service.countMinions(townUuid); }
    @Override public int maxMinions(UUID townUuid) { return service.maxMinions(townUuid); }
    @Override public boolean canPlace(Player player, Location location, String typeId) { return service.canPlace(player, location, typeId); }
    @Override public CompletableFuture<OperationResult> place(Player player, Location location, ItemStack minionItem) { return service.place(player, location, minionItem); }
    @Override public CompletableFuture<OperationResult> pickup(Player player, UUID minionId) { return service.pickup(player, minionId); }
    @Override public CompletableFuture<OperationResult> move(Player player, UUID minionId, Location targetLocation) { return service.move(player, minionId, targetLocation); }
    @Override public CompletableFuture<OperationResult> upgrade(Player player, UUID minionId) { return service.upgrade(player, minionId); }
    @Override public TownMinionMenuData menuData(Player viewer) { return service.townData(viewer); }
    @Override public Optional<MinionMenuData> menuData(Player viewer, UUID minionId) { return service.minionData(viewer, minionId); }
    @Override public Optional<MinionMenuData> menuDataByIndex(Player viewer, int index) { return service.minionByIndex(viewer, index); }
    @Override public Optional<ItemStack> menuIcon(Player viewer, UUID minionId) { return service.menuIcon(viewer, minionId); }
    @Override public Optional<ItemStack> menuIconByIndex(Player viewer, int index) { return service.menuIconByIndex(viewer, index); }
    @Override public Optional<MinionMenuData> selectedMenuData(Player viewer) { return service.selectedMinion(viewer); }
    @Override public void registerListener(MinionsListener listener) { service.registerListener(listener); }
}


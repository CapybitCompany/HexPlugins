package hexnpc.workflow.menu;

import hexnpc.HexNpcPlugin;
import hexnpc.data.PlayerDataService;
import hexnpc.util.LegacyFormat;
import hexnpc.workflow.WorkflowRegistry;
import hexnpc.workflow.WorkflowService;
import hexnpc.workflow.model.WorkflowMenu;
import hexnpc.workflow.model.WorkflowMenuItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WorkflowMenuService {
    private final HexNpcPlugin plugin;
    private final WorkflowRegistry registry;
    private final PlayerDataService playerData;
    private final WorkflowMenuRenderer renderer;
    private final Logger logger;
    private WorkflowService workflowService;

    public WorkflowMenuService(HexNpcPlugin plugin, WorkflowRegistry registry,
                               PlayerDataService playerData, WorkflowMenuRenderer renderer,
                               Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.playerData = playerData;
        this.renderer = renderer;
        this.logger = logger;
    }

    public void bindWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public boolean exists(String id) { return registry.hasMenu(id); }
    public List<String> ids() { return registry.menuIds(); }

    public CompletableFuture<Boolean> open(Player player, String menuId) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(false);
        WorkflowMenu menu = registry.menu(menuId).orElse(null);
        if (menu == null) {
            sendMain(player, "&cTo menu jest obecnie niedostępne.");
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Void> load = playerData.available()
                ? playerData.ensureLoaded(player.getUniqueId())
                : CompletableFuture.completedFuture(null);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        load.whenComplete((ignored, error) -> runMain(() -> {
            if (!player.isOnline()) {
                result.complete(false);
                return;
            }
            if (error != null && playerData.available()) {
                logger.log(Level.WARNING, "HexNPC: failed to prepare workflow menu '" + menu.id() + "' for " + player.getName(), error);
                player.sendMessage(LegacyFormat.component("&cDane profilu są chwilowo niedostępne."));
                result.complete(false);
                return;
            }
            try {
                player.openInventory(renderer.render(player, menu));
                result.complete(true);
            } catch (RuntimeException | LinkageError ex) {
                logger.log(Level.SEVERE, "HexNPC: failed to open workflow menu '" + menu.id() + "' for " + player.getName(), ex);
                player.sendMessage(LegacyFormat.component("&cNie udało się otworzyć menu."));
                result.complete(false);
            }
        }));
        return result;
    }

    public List<hexnpc.workflow.action.WorkflowAction> actions(String menuId, int slot, String click) {
        WorkflowMenu menu = registry.menu(menuId).orElse(null);
        if (menu == null) return List.of();
        for (WorkflowMenuItem item : menu.items().values()) {
            if (item.slot() == slot) return item.actionsFor(click);
        }
        return List.of();
    }

    public String itemId(String menuId, int slot) {
        WorkflowMenu menu = registry.menu(menuId).orElse(null);
        if (menu == null) return "";
        for (WorkflowMenuItem item : menu.items().values()) if (item.slot() == slot) return item.id();
        return "";
    }

    public void executeClick(Player player, String menuId, int slot, String click) {
        if (workflowService == null) return;
        List<hexnpc.workflow.action.WorkflowAction> actions = actions(menuId, slot, click);
        if (actions.isEmpty()) return;
        String itemId = itemId(menuId, slot);
        workflowService.startInline(player, actions, "menu:" + menuId, itemId, click);
    }

    private void runMain(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) runnable.run();
        else plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    private void sendMain(Player player, String message) {
        runMain(() -> { if (player.isOnline()) player.sendMessage(LegacyFormat.component(message)); });
    }
}

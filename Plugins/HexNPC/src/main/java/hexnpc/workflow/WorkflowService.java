package hexnpc.workflow;

import hexnpc.HexNpcPlugin;
import hexnpc.data.PlayerDataService;
import hexnpc.shop.ShopService;
import hexnpc.util.LegacyFormat;
import hexnpc.workflow.action.*;
import hexnpc.workflow.input.AnvilInputResult;
import hexnpc.workflow.input.AnvilInputService;
import hexnpc.workflow.menu.WorkflowMenuService;
import hexnpc.workflow.model.WorkflowDefinition;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Generic configured workflow orchestrator. No custom-tag-specific Java branches live here. */
public final class WorkflowService {
    private final HexNpcPlugin plugin;
    private final WorkflowRegistry registry;
    private final PlayerDataService playerData;
    private final ValueResolver resolver;
    private final AnvilInputService anvilInput;
    private final WorkflowMenuService menus;
    private final Supplier<ShopService> shopServiceSupplier;
    private final Logger logger;
    private final ActionRegistry actionRegistry = new ActionRegistry();
    private final Map<UUID, WorkflowExecution> active = new ConcurrentHashMap<>();

    public WorkflowService(HexNpcPlugin plugin,
                           WorkflowRegistry registry,
                           PlayerDataService playerData,
                           ValueResolver resolver,
                           AnvilInputService anvilInput,
                           WorkflowMenuService menus,
                           Supplier<ShopService> shopServiceSupplier,
                           Logger logger) {
        this.plugin = plugin;
        this.registry = registry;
        this.playerData = playerData;
        this.resolver = resolver;
        this.anvilInput = anvilInput;
        this.menus = menus;
        this.shopServiceSupplier = shopServiceSupplier;
        this.logger = logger;
        registerExecutors();
    }

    public boolean exists(String workflowId) {
        return registry.hasWorkflow(workflowId);
    }

    /**
     * True only when the configured workflow exists and every infrastructure
     * required before it starts is currently available. This lets transactional
     * callers fail before charging a player.
     */
    public boolean canRun(String workflowId) {
        return unavailableReason(workflowId).isEmpty();
    }

    /** Empty string means runnable; otherwise returns an admin-facing reason. */
    public String unavailableReason(String workflowId) {
        WorkflowDefinition definition = registry.workflow(workflowId).orElse(null);
        if (definition == null) return "workflow not found";
        if (usesPlayerData(definition.actions(), new java.util.HashSet<>())) {
            if (!playerData.available()) return "player-data storage unavailable: " + playerData.status();
            if (!playerData.ready()) return "player-data storage not ready: " + playerData.status();
        }
        return "";
    }

    public CompletableFuture<ActionResult> startWorkflow(Player player, String workflowId,
                                                         String sourceMenu, String sourceItem, String sourceClick) {
        WorkflowDefinition definition = registry.workflow(workflowId).orElse(null);
        if (definition == null) {
            if (player != null) sendMain(player, "&cTa funkcja jest obecnie niedostępna.");
            return CompletableFuture.completedFuture(ActionResult.FAILURE);
        }
        return start(player, definition.id(), definition.actions(), sourceMenu, sourceItem, sourceClick);
    }

    public CompletableFuture<ActionResult> startInline(Player player, List<WorkflowAction> actions,
                                                       String sourceMenu, String sourceItem, String sourceClick) {
        return start(player, "inline", actions, sourceMenu, sourceItem, sourceClick);
    }

    private CompletableFuture<ActionResult> start(Player player, String id, List<WorkflowAction> actions,
                                                  String sourceMenu, String sourceItem, String sourceClick) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(ActionResult.CANCELLED);
        cancel(player.getUniqueId(), false);
        WorkflowContext context = new WorkflowContext(player.getUniqueId(), id, sourceMenu, sourceItem, sourceClick);
        WorkflowExecution execution = new WorkflowExecution(player.getUniqueId(), id, context);
        active.put(player.getUniqueId(), execution);
        if (registry.debug()) logger.info("[HexNPC] Workflow " + id + " started for " + player.getName());

        CompletableFuture<Void> load = playerData.available()
                ? playerData.ensureLoaded(player.getUniqueId())
                : CompletableFuture.completedFuture(null);
        CompletableFuture<ActionResult> out = load.handle((ignored, error) -> {
            if (error != null && usesPlayerData(actions)) {
                sendMain(player, "&cDane profilu są chwilowo niedostępne.");
                return false;
            }
            return true;
        }).thenCompose(ok -> ok ? executeActions(player, actions, context, 0)
                : CompletableFuture.completedFuture(ActionResult.FAILURE));
        out.whenComplete((result, error) -> {
            active.remove(player.getUniqueId(), execution);
            if (registry.debug()) logger.info("[HexNPC] Workflow " + id + " -> "
                    + (error == null ? String.valueOf(result) : "ERROR " + error.getMessage()));
            if (error != null) logger.log(Level.WARNING, "HexNPC: workflow '" + id + "' failed for " + player.getName(), error);
        });
        return out;
    }

    CompletableFuture<ActionResult> executeActions(Player player, List<WorkflowAction> actions,
                                                   WorkflowContext context, int index) {
        if (context.cancelled()) return CompletableFuture.completedFuture(ActionResult.CANCELLED);
        if (actions == null || index >= actions.size()) return CompletableFuture.completedFuture(ActionResult.SUCCESS);
        WorkflowAction action = actions.get(index);
        return actionRegistry.execute(player, action, context)
                .handle((result, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "HexNPC: workflow action '" + action.type() + "' failed", error);
                        return ActionResult.FAILURE;
                    }
                    ActionResult safe = result == null ? ActionResult.FAILURE : result;
                    if (registry.debug()) logger.info("[HexNPC] Action " + (index + 1) + " " + action.type() + " -> " + safe);
                    return safe;
                }).thenCompose(result -> result.continues()
                        ? executeActions(player, actions, context, index + 1)
                        : CompletableFuture.completedFuture(result));
    }

    public void cancel(UUID playerId, boolean closeInput) {
        WorkflowExecution execution = active.remove(playerId);
        if (execution != null) execution.context().cancel();
        anvilInput.cancel(playerId, closeInput);
    }

    public void cancelAll() {
        for (UUID uuid : List.copyOf(active.keySet())) cancel(uuid, true);
        anvilInput.cancelAll();
    }

    private void registerExecutors() {
        actionRegistry.register("message", (player, raw, context) -> {
            MessageAction action = (MessageAction) raw;
            return main(() -> {
                player.sendMessage(LegacyFormat.component(resolver.resolve(action.text(), player, context)));
                return ActionResult.SUCCESS;
            });
        });

        actionRegistry.register("open_menu", (player, raw, context) -> {
            OpenMenuAction action = (OpenMenuAction) raw;
            return menus.open(player, resolver.resolve(action.menu(), player, context))
                    .thenApply(ok -> ok ? ActionResult.SUCCESS : ActionResult.FAILURE);
        });

        actionRegistry.register("open_shop", (player, raw, context) -> {
            OpenShopAction action = (OpenShopAction) raw;
            return main(() -> {
                ShopService shopService = shopServiceSupplier.get();
                if (shopService == null) return ActionResult.FAILURE;
                return shopService.openShop(player, resolver.resolve(action.shop(), player, context))
                        ? ActionResult.SUCCESS : ActionResult.FAILURE;
            });
        });

        actionRegistry.register("close_menu", (player, raw, context) -> main(() -> {
            player.closeInventory();
            return ActionResult.SUCCESS;
        }));

        actionRegistry.register("command", (player, raw, context) -> {
            CommandAction action = (CommandAction) raw;
            return main(() -> {
                if (!action.allowInputVariables() && action.command().contains("{input:")) return ActionResult.FAILURE;
                String command = resolver.resolveCommand(action.command(), player, context, action.allowInputVariables()).trim();
                if (command.startsWith("/")) command = command.substring(1);
                if (command.isBlank() || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return ActionResult.FAILURE;
                boolean ok = action.executor() == CommandAction.Executor.PLAYER
                        ? player.performCommand(command)
                        : Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                return ok ? ActionResult.SUCCESS : ActionResult.FAILURE;
            });
        });

        actionRegistry.register("run_workflow", (player, raw, context) -> {
            RunWorkflowAction action = (RunWorkflowAction) raw;
            WorkflowDefinition nested = registry.workflow(action.workflow()).orElse(null);
            if (nested == null) return CompletableFuture.completedFuture(ActionResult.FAILURE);
            return executeActions(player, nested.actions(), context, 0);
        });

        actionRegistry.register("set_player_data", (player, raw, context) -> {
            SetPlayerDataAction action = (SetPlayerDataAction) raw;
            String value = resolver.resolve(action.value(), player, context);
            return playerData.set(player.getUniqueId(), action.key(), value)
                    .handle((ignored, error) -> {
                        if (error == null) return ActionResult.SUCCESS;
                        sendMain(player, "&cNie udało się zapisać danych. Spróbuj ponownie za chwilę.");
                        return ActionResult.FAILURE;
                    });
        });

        actionRegistry.register("delete_player_data", (player, raw, context) -> {
            DeletePlayerDataAction action = (DeletePlayerDataAction) raw;
            return playerData.delete(player.getUniqueId(), action.key())
                    .handle((ignored, error) -> {
                        if (error == null) return ActionResult.SUCCESS;
                        sendMain(player, "&cNie udało się zmienić danych. Spróbuj ponownie za chwilę.");
                        return ActionResult.FAILURE;
                    });
        });

        actionRegistry.register("anvil_input", (player, raw, context) -> {
            AnvilInputAction action = (AnvilInputAction) raw;
            CompletableFuture<ActionResult> result = new CompletableFuture<>();
            main(() -> {
                anvilInput.request(player, action, context).whenComplete((input, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    if (input == null || input.status() == AnvilInputResult.Status.CANCELLED) {
                        executeActions(player, action.onCancel(), context, 0).whenComplete((ignored, cancelError) -> {
                            if (cancelError != null) result.completeExceptionally(cancelError);
                            else result.complete(ActionResult.CANCELLED);
                        });
                        return;
                    }
                    context.putInput(action.id(), input.value());
                    result.complete(ActionResult.SUCCESS);
                });
                return ActionResult.SUCCESS;
            }).whenComplete((ignored, error) -> {
                if (error != null) result.completeExceptionally(error);
            });
            return result;
        });

        actionRegistry.register("condition", (player, raw, context) -> {
            ConditionAction action = (ConditionAction) raw;
            return main(() -> evaluateConditions(player, action.conditions(), context))
                    .thenCompose(match -> executeActions(player,
                            match == ActionResult.SUCCESS ? action.thenActions() : action.elseActions(), context, 0));
        });
    }

    private ActionResult evaluateConditions(Player player, List<ConditionDefinition> conditions, WorkflowContext context) {
        for (ConditionDefinition condition : conditions) {
            if (!evaluateCondition(player, condition, context)) return ActionResult.FAILURE;
        }
        return ActionResult.SUCCESS;
    }

    private boolean evaluateCondition(Player player, ConditionDefinition condition, WorkflowContext context) {
        return switch (condition.type()) {
            case "permission" -> player.hasPermission(resolver.resolve(condition.value(), player, context));
            case "player_data_exists" -> !playerData.getCached(player.getUniqueId(), condition.key()).isEmpty();
            case "player_data_equals" -> playerData.getCached(player.getUniqueId(), condition.key())
                    .equals(resolver.resolve(condition.value(), player, context));
            case "placeholder_equals" -> placeholder(player, condition.placeholder())
                    .equals(resolver.resolve(condition.value(), player, context));
            case "placeholder_numeric" -> compareNumeric(
                    placeholder(player, condition.placeholder()), condition.operator(),
                    resolver.resolve(condition.value(), player, context));
            default -> false;
        };
    }

    private String placeholder(Player player, String template) {
        if (template == null) return "";
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return template;
        try { return PlaceholderAPI.setPlaceholders(player, template); }
        catch (Throwable ignored) { return template; }
    }

    private boolean compareNumeric(String leftRaw, String operator, String rightRaw) {
        try {
            double left = Double.parseDouble(leftRaw.trim().replace(',', '.'));
            double right = Double.parseDouble(rightRaw.trim().replace(',', '.'));
            return switch (operator) {
                case ">" -> left > right;
                case ">=" -> left >= right;
                case "<" -> left < right;
                case "<=" -> left <= right;
                case "!=", "<>" -> Double.compare(left, right) != 0;
                default -> Double.compare(left, right) == 0;
            };
        } catch (Exception ignored) {
            return false;
        }
    }

    private CompletableFuture<ActionResult> main(Supplier<ActionResult> work) {
        CompletableFuture<ActionResult> result = new CompletableFuture<>();
        Runnable task = () -> {
            try { result.complete(work.get()); }
            catch (Throwable t) { result.completeExceptionally(t); }
        };
        if (Bukkit.isPrimaryThread()) task.run(); else plugin.getServer().getScheduler().runTask(plugin, task);
        return result;
    }

    private void sendMain(Player player, String message) {
        main(() -> {
            if (player.isOnline()) player.sendMessage(LegacyFormat.component(message));
            return ActionResult.SUCCESS;
        });
    }

    private boolean usesPlayerData(List<WorkflowAction> actions) {
        return usesPlayerData(actions, new java.util.HashSet<>());
    }

    private boolean usesPlayerData(List<WorkflowAction> actions, java.util.Set<String> visitingWorkflows) {
        if (actions == null) return false;
        for (WorkflowAction action : actions) {
            if (action instanceof SetPlayerDataAction || action instanceof DeletePlayerDataAction) return true;
            if (action instanceof AnvilInputAction anvil && anvil.initialText().contains("{data:")) return true;
            if (action instanceof ConditionAction condition) {
                for (ConditionDefinition def : condition.conditions()) if (def.type().startsWith("player_data")) return true;
                if (usesPlayerData(condition.thenActions(), visitingWorkflows)
                        || usesPlayerData(condition.elseActions(), visitingWorkflows)) return true;
            }
            if (action instanceof RunWorkflowAction nested) {
                String id = nested.workflow() == null ? "" : nested.workflow().trim().toLowerCase(java.util.Locale.ROOT);
                if (!id.isEmpty() && visitingWorkflows.add(id)) {
                    WorkflowDefinition definition = registry.workflow(id).orElse(null);
                    try {
                        if (definition != null && usesPlayerData(definition.actions(), visitingWorkflows)) return true;
                    } finally {
                        visitingWorkflows.remove(id);
                    }
                }
            }
        }
        return false;
    }
}

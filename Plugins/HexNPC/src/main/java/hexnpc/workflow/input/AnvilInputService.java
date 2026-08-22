package hexnpc.workflow.input;

import hexnpc.HexNpcPlugin;
import hexnpc.util.LegacyFormat;
import hexnpc.workflow.ValueResolver;
import hexnpc.workflow.WorkflowContext;
import hexnpc.workflow.action.AnvilInputAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Controlled anvil text input. One active session per player. */
public final class AnvilInputService implements Listener {
    private final HexNpcPlugin plugin;
    private final ValueResolver resolver;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AnvilInputService(HexNpcPlugin plugin, ValueResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
    }

    public CompletableFuture<AnvilInputResult> request(Player player, AnvilInputAction action, WorkflowContext context) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(AnvilInputResult.cancelled());
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<AnvilInputResult> deferred = new CompletableFuture<>();
            plugin.getServer().getScheduler().runTask(plugin, () -> request(player, action, context)
                    .whenComplete((result, error) -> {
                        if (error != null) deferred.completeExceptionally(error); else deferred.complete(result);
                    }));
            return deferred;
        }

        cancel(player.getUniqueId(), false);
        CompletableFuture<AnvilInputResult> future = new CompletableFuture<>();
        AnvilInputHolder holder = new AnvilInputHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL,
                LegacyFormat.component(resolver.resolve(action.title(), player, context)));
        holder.bind(inventory);

        String initial = resolver.resolve(action.initialText(), player, context);
        ItemStack input = new ItemStack(action.material() == null ? Material.NAME_TAG : action.material());
        ItemMeta meta = input.getItemMeta();
        if (meta != null) {
            if (!initial.isBlank()) meta.displayName(Component.text(initial));
            else meta.displayName(LegacyFormat.component(resolver.resolve(action.itemName(), player, context)));
            meta.addItemFlags(ItemFlag.values());
            input.setItemMeta(meta);
        }
        inventory.setItem(0, input);
        Session session = new Session(player.getUniqueId(), action, context, holder, future);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
        return future;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof AnvilInputHolder holder)) return;
        Session session = sessions.get(holder.playerId());
        if (session == null || session.holder() != holder) return;
        AnvilView view = event.getView();
        view.setRepairCost(0);
        view.setMaximumRepairCost(40);
        ItemStack base = event.getInventory().getItem(0);
        if (base == null || base.getType().isAir()) return;
        ItemStack result = base.clone();
        String rename = view.getRenameText();
        ItemMeta meta = result.getItemMeta();
        if (meta != null && rename != null && !rename.isBlank()) {
            meta.displayName(Component.text(rename));
            result.setItemMeta(meta);
        }
        event.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AnvilInputHolder holder)) return;
        Session session = sessions.get(holder.playerId());
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        if (session == null || !(event.getWhoClicked() instanceof Player player)) return;
        if (!player.getUniqueId().equals(holder.playerId())) return;
        int raw = event.getRawSlot();
        if (raw != 2) return;
        if (!(event.getView() instanceof AnvilView anvilView)) return;

        String rawText = anvilView.getRenameText();
        ValidationResult validation = validate(rawText, session.action());
        if (!validation.valid()) {
            player.sendMessage(LegacyFormat.component(validation.message()));
            return;
        }
        if (!sessions.remove(player.getUniqueId(), session)) return;
        session.future().complete(AnvilInputResult.submitted(validation.value()));
        plugin.getServer().getScheduler().runTask(plugin, player::closeInventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AnvilInputHolder)) return;
        int top = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < top) {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof AnvilInputHolder holder)) return;
        Session session = sessions.get(holder.playerId());
        if (session == null || session.holder() != holder) return;
        if (sessions.remove(holder.playerId(), session)) session.future().complete(AnvilInputResult.cancelled());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    public void cancel(UUID playerId, boolean closeInventory) {
        Session session = sessions.remove(playerId);
        if (session != null) session.future().complete(AnvilInputResult.cancelled());
        if (closeInventory) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()
                    && player.getOpenInventory().getTopInventory().getHolder() instanceof AnvilInputHolder) {
                player.closeInventory();
            }
        }
    }

    public void cancelAll() {
        for (UUID uuid : java.util.List.copyOf(sessions.keySet())) cancel(uuid, true);
    }

    private ValidationResult validate(String raw, AnvilInputAction action) {
        AnvilInputAction.Validation rules = action.validation();
        String value = raw == null ? "" : raw;
        if (rules.trim()) value = value.trim();
        if (rules.required() && value.isEmpty()) return ValidationResult.fail(action.messages().required());
        if (value.length() < rules.minLength()) return ValidationResult.fail(action.messages().tooShort());
        if (value.length() > rules.maxLength()) return ValidationResult.fail(action.messages().tooLong());
        if (!rules.allowColors() && (value.indexOf('&') >= 0 || value.indexOf('§') >= 0)) {
            return ValidationResult.fail(action.messages().invalid());
        }
        if (!rules.allowMiniMessage() && (value.indexOf('<') >= 0 || value.indexOf('>') >= 0)) {
            return ValidationResult.fail(action.messages().invalid());
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            return ValidationResult.fail(action.messages().invalid());
        }
        if (!rules.allowedPattern().isBlank() && !Pattern.compile(rules.allowedPattern()).matcher(value).matches()) {
            return ValidationResult.fail(action.messages().invalid());
        }
        for (String denied : rules.denyPatterns()) {
            if (Pattern.compile(denied).matcher(value).matches()) return ValidationResult.fail(action.messages().invalid());
        }
        return new ValidationResult(true, value, "");
    }

    private record Session(UUID playerId, AnvilInputAction action, WorkflowContext context,
                           AnvilInputHolder holder, CompletableFuture<AnvilInputResult> future) {}

    private record ValidationResult(boolean valid, String value, String message) {
        static ValidationResult fail(String message) { return new ValidationResult(false, "", message); }
    }
}

package hexnpc.workflow.input;

import hexnpc.HexNpcPlugin;
import hexnpc.util.LegacyFormat;
import hexnpc.workflow.ValueResolver;
import hexnpc.workflow.WorkflowContext;
import hexnpc.workflow.action.AnvilInputAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.inventory.MenuType;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
    private final NamespacedKey sessionMarkerKey;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AnvilInputService(HexNpcPlugin plugin, ValueResolver resolver) {
        this.plugin = plugin;
        this.resolver = resolver;
        this.sessionMarkerKey = new NamespacedKey(plugin, "workflow_anvil_session");
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
        Component title = LegacyFormat.component(resolver.resolve(action.title(), player, context));

        /*
         * IMPORTANT: do not use Bukkit.createInventory(..., InventoryType.ANVIL).
         * On modern Paper that creates a generic/custom inventory wrapper rather
         * than a real anvil menu, which can make rename text and prepare events
         * unreliable. MenuType.ANVIL creates the native typed AnvilView intended
         * for programmatic anvil UIs on Paper 1.21.x.
         */
        AnvilView view = MenuType.ANVIL.create(player, title);
        Inventory inventory = view.getTopInventory();

        String initial = resolver.resolve(action.initialText(), player, context);
        String token = UUID.randomUUID().toString();
        Session session = new Session(player.getUniqueId(), action, context, view, inventory, future, token);
        session.renameText(initial);
        sessions.put(player.getUniqueId(), session);

        ItemStack input = new ItemStack(action.material() == null ? Material.NAME_TAG : action.material());
        ItemMeta meta = input.getItemMeta();
        if (meta != null) {
            // A single space keeps the initial rename field visually empty while
            // still producing a native output. Existing data is used when editing.
            meta.customName(Component.text(initial.isBlank() ? " " : initial));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(sessionMarkerKey, PersistentDataType.STRING, token);
            input.setItemMeta(meta);
        }
        // Put the marked fake input in the native anvil before opening it. The marker
        // lets event handlers recognise this exact workflow session even when Paper
        // exposes a different Inventory wrapper object for the same native view.
        inventory.setItem(0, input);

        player.openInventory(view);
        view.setRepairCost(0);
        view.setMaximumRepairCost(40);
        return future;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        Session session = activeSession(player, event.getView().getTopInventory());
        if (session == null) return;

        AnvilView view = event.getView();
        view.setRepairCost(0);
        view.setMaximumRepairCost(40);

        /*
         * With a native MenuType.ANVIL view, let Minecraft/Paper build the result
         * item. We only observe the rename text and keep the XP cost at zero.
         * Replacing PrepareAnvilEvent#getResult manually can overwrite the native
         * rename state that arrived from the client.
         */
        String rename = firstNonBlank(
                view.getRenameText(),
                editableNameOf(event.getResult()),
                session.renameText()
        );
        if (!rename.isBlank()) session.renameText(rename);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = activeSession(player, event.getView().getTopInventory());
        if (session == null) return;

        // Every interaction while our controlled anvil is open is denied. The
        // result slot is handled explicitly below and never transfers the fake
        // item into the player's inventory.
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        boolean resultSlot = event.getSlotType() == InventoryType.SlotType.RESULT
                || event.getRawSlot() == 2;
        if (!resultSlot) return;

        /*
         * Do not let a transient empty getRenameText() overwrite a valid value.
         * The clicked result item's custom name is the most useful fallback on
         * Paper 1.21.x because it is exactly what the player sees and clicks.
         */
        String viewText = event.getView() instanceof AnvilView anvilView
                ? anvilView.getRenameText()
                : "";
        String resultText = editableNameOf(event.getCurrentItem());
        if (resultText.isBlank()) {
            resultText = editableNameOf(event.getView().getTopInventory().getItem(2));
        }
        String inputText = editableNameOf(event.getView().getTopInventory().getItem(0));
        String rawText = firstNonBlank(viewText, resultText, session.renameText(), inputText);
        session.renameText(rawText);

        ValidationResult validation = validate(rawText, session.action());
        if (!validation.valid()) {
            if (rawText == null || rawText.isBlank()) {
                plugin.getLogger().warning("HexNPC anvil input: empty rename text on result click for "
                        + player.getName() + " [view=" + event.getView().getClass().getName()
                        + ", top=" + event.getView().getTopInventory().getClass().getName()
                        + ", current=" + (event.getCurrentItem() == null ? "null" : event.getCurrentItem().getType()) + "]");
            }
            player.sendMessage(LegacyFormat.component(validation.message()));
            return;
        }
        if (!sessions.remove(player.getUniqueId(), session)) return;
        // A native anvil normally returns its input item to the player when it closes.
        // Our input/result are UI-only objects, so clear every top slot before closing
        // and additionally purge session-marked copies on the next tick as a safety net.
        clearSessionInventory(session);
        // InventoryClickEvent is still being processed here. Close the anvil and
        // resume the workflow on the next tick to avoid re-entrant inventory
        // opens/closes from subsequent workflow actions.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.closeInventory();
            purgeSessionItems(player, session);
            session.future().complete(AnvilInputResult.submitted(validation.value()));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (activeSession(player, event.getView().getTopInventory()) == null) return;
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
        if (!(event.getPlayer() instanceof Player player)) return;
        Session session = activeSession(player, event.getView().getTopInventory());
        if (session == null) return;
        if (!sessions.remove(player.getUniqueId(), session)) return;
        clearSessionInventory(session);

        // Never resume on-cancel while Bukkit is still inside InventoryCloseEvent.
        // Re-opening a chest/menu re-entrantly from this event can create a ghost
        // inventory whose holder/listener state is inconsistent and whose items
        // can be moved like a normal chest. Resume one tick later instead.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            purgeSessionItems(player, session);
            session.future().complete(AnvilInputResult.cancelled());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    public void cancel(UUID playerId, boolean closeInventory) {
        Session session = sessions.remove(playerId);
        if (session == null) return;
        clearSessionInventory(session);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) purgeSessionItems(player, session);
        session.future().complete(AnvilInputResult.cancelled());
        if (closeInventory && player != null && player.isOnline()
                && player.getOpenInventory() instanceof AnvilView) {
            player.closeInventory();
        }
    }

    public void cancelAll() {
        for (UUID uuid : java.util.List.copyOf(sessions.keySet())) cancel(uuid, true);
    }


    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String editableNameOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "";
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return "";

        Component name = null;
        if (meta.hasCustomName() && meta.customName() != null) {
            name = meta.customName();
        } else if (meta.hasDisplayName() && meta.displayName() != null) {
            // displayName() is an obsolete alias on newer Paper but remains a
            // useful compatibility fallback for implementations exposing the
            // anvil-created name through the older Bukkit/Paper accessor.
            name = meta.displayName();
        }
        return name == null ? "" : PlainTextComponentSerializer.plainText().serialize(name);
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

    private Session activeSession(Player player, Inventory topInventory) {
        if (player == null || topInventory == null) return null;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || topInventory.getType() != InventoryType.ANVIL) return null;

        // Do not compare Inventory wrappers with ==. Native MenuType views may expose
        // different wrapper instances across prepare/click/close events. Identify our
        // virtual anvil by a unique PDC marker carried by its fake input/result item.
        if (hasSessionMarker(topInventory.getItem(0), session)
                || hasSessionMarker(topInventory.getItem(2), session)) return session;
        return null;
    }

    private boolean hasSessionMarker(ItemStack stack, Session session) {
        if (stack == null || stack.getType().isAir() || session == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        String token = meta.getPersistentDataContainer().get(sessionMarkerKey, PersistentDataType.STRING);
        return session.token().equals(token);
    }

    private void clearSessionInventory(Session session) {
        if (session == null) return;
        Inventory inventory = session.inventory();
        if (inventory == null) return;
        for (int slot = 0; slot < Math.min(3, inventory.getSize()); slot++) inventory.setItem(slot, null);
    }

    private void purgeSessionItems(Player player, Session session) {
        if (player == null || session == null) return;
        ItemStack cursor = player.getItemOnCursor();
        if (hasSessionMarker(cursor, session)) player.setItemOnCursor(null);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (hasSessionMarker(contents[slot], session)) player.getInventory().setItem(slot, null);
        }
    }

    private static final class Session {
        private final UUID playerId;
        private final AnvilInputAction action;
        private final WorkflowContext context;
        private final AnvilView view;
        private final Inventory inventory;
        private final CompletableFuture<AnvilInputResult> future;
        private final String token;
        private volatile String renameText = "";

        private Session(UUID playerId, AnvilInputAction action, WorkflowContext context,
                        AnvilView view, Inventory inventory, CompletableFuture<AnvilInputResult> future,
                        String token) {
            this.playerId = playerId;
            this.action = action;
            this.context = context;
            this.view = view;
            this.inventory = inventory;
            this.future = future;
            this.token = token;
        }

        UUID playerId() { return playerId; }
        AnvilInputAction action() { return action; }
        WorkflowContext context() { return context; }
        AnvilView view() { return view; }
        Inventory inventory() { return inventory; }
        CompletableFuture<AnvilInputResult> future() { return future; }
        String token() { return token; }
        String renameText() { return renameText; }
        void renameText(String value) { renameText = value == null ? "" : value; }
    }

    private record ValidationResult(boolean valid, String value, String message) {
        static ValidationResult fail(String message) { return new ValidationResult(false, "", message); }
    }
}

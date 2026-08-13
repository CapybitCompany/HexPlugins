package hexcustomitems.service;

import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.MessageAction;
import hexcustomitems.model.PotionEffectSpec;
import hexcustomitems.model.SelfPotionAction;
import hexcustomitems.model.SoundAction;
import hexcustomitems.model.SpecialAction;
import hexcustomitems.util.PlaceholderUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.Map;
import java.util.Objects;

/**
 * Führt die konfigurierten Aktionen eines Items in Reihenfolge aus.
 * Generisch und typunabhängig - neue Aktionstypen werden hier ergänzt.
 */
public final class ActionExecutor {

    private final JavaPlugin plugin;
    private final MessageService messageService;
    private final CommandDispatcher commandDispatcher;
    private final SpecialItemActionService specialActions;

    public ActionExecutor(JavaPlugin plugin, MessageService messageService) {
        this(plugin, messageService, CommandDispatcher.BUKKIT, null);
    }

    public ActionExecutor(JavaPlugin plugin, MessageService messageService, CommandDispatcher commandDispatcher) {
        this(plugin, messageService, commandDispatcher, null);
    }

    public ActionExecutor(JavaPlugin plugin, MessageService messageService,
                          CommandDispatcher commandDispatcher, SpecialItemActionService specialActions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.commandDispatcher = Objects.requireNonNull(commandDispatcher, "commandDispatcher");
        this.specialActions = specialActions;
    }

    public boolean execute(Player user, EquipmentSlot hand, CustomItemDefinition definition, int amount) {
        for (ItemAction action : definition.actions()) {
            switch (action) {
                case CommandAction command -> runCommands(user, definition, command, amount);
                case SelfPotionAction potion -> applyPotion(user, potion);
                case MessageAction message -> messageService.sendActionMessage(user, message.message());
                case SoundAction sound -> playSound(user, sound);
                case SpecialAction special -> {
                    if (specialActions == null || !specialActions.execute(user, hand, definition, special)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void runCommands(Player user, CustomItemDefinition definition, CommandAction action, int amount) {
        Map<String, String> placeholders = commandPlaceholders(user, definition, amount);
        for (String template : action.commands()) {
            String command = PlaceholderUtil.apply(template, placeholders);
            if (command.isBlank()) {
                continue;
            }
            if (action.executor() == CommandExecutorType.PLAYER) {
                commandDispatcher.dispatch(user, command);
            } else {
                commandDispatcher.dispatch(plugin.getServer().getConsoleSender(), command);
            }
        }
    }

    private void applyPotion(Player user, SelfPotionAction action) {
        PotionEffectSpec effect = action.effect();
        user.addPotionEffect(new PotionEffect(
                effect.type(),
                effect.durationSeconds() * 20,
                effect.amplifier(),
                true,
                true,
                true
        ));
    }

    private void playSound(Player user, SoundAction action) {
        if (action.sound().isBlank()) {
            return;
        }
        if (action.delayTicks() <= 0) {
            user.playSound(user.getLocation(), action.sound(), action.volume(), action.pitch());
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> user.playSound(user.getLocation(), action.sound(), action.volume(), action.pitch()),
                action.delayTicks());
    }

    private Map<String, String> commandPlaceholders(Player user, CustomItemDefinition definition, int amount) {
        Location location = user.getLocation();
        return Map.of(
                "player", user.getName(),
                "uuid", user.getUniqueId().toString(),
                "item_id", definition.id(),
                "world", location.getWorld() == null ? "" : location.getWorld().getName(),
                "x", String.valueOf(location.getBlockX()),
                "y", String.valueOf(location.getBlockY()),
                "z", String.valueOf(location.getBlockZ()),
                "amount", String.valueOf(amount)
        );
    }
}

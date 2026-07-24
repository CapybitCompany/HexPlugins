package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MessageService {

    private final Supplier<HexCustomItemsConfig> configSupplier;

    public MessageService(Supplier<HexCustomItemsConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void sendNoPermission(CommandSender sender) {
        send(sender, configSupplier.get().messages().noPermission(), Map.of());
    }

    public void sendUseNoPermission(CommandSender sender) {
        send(sender, configSupplier.get().messages().useNoPermission(), Map.of());
    }

    public void sendPlayerNotFound(CommandSender sender) {
        send(sender, configSupplier.get().messages().playerNotFound(), Map.of());
    }

    public void sendInvalidNumber(CommandSender sender) {
        send(sender, configSupplier.get().messages().invalidNumber(), Map.of());
    }

    public void sendItemNotFound(CommandSender sender, String itemId) {
        send(sender, configSupplier.get().messages().itemNotFound(), Map.of("item_id", itemId));
    }

    public void sendUsageMain(CommandSender sender) {
        send(sender, configSupplier.get().messages().usageMain(), Map.of());
    }

    public void sendUsageGive(CommandSender sender) {
        send(sender, configSupplier.get().messages().usageGive(), Map.of());
    }

    public void sendReloaded(CommandSender sender) {
        send(sender, configSupplier.get().messages().reloaded(), Map.of());
    }

    public void sendGivenSender(CommandSender sender, String amount, Component itemName, String target) {
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("amount", amount),
                Placeholder.unparsed("target", target),
                Placeholder.component("item_name", itemName)
        );
        sender.sendMessage(prefix().append(TextUtil.parse(configSupplier.get().messages().givenSender(), resolver)));
    }

    public void sendGivenTarget(CommandSender sender, String amount, Component itemName) {
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("amount", amount),
                Placeholder.component("item_name", itemName)
        );
        sender.sendMessage(prefix().append(TextUtil.parse(configSupplier.get().messages().givenTarget(), resolver)));
    }

    public void sendList(CommandSender sender, String items) {
        send(sender, configSupplier.get().messages().listHeader(), Map.of("items", items));
    }

    public void sendDropBlocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().dropBlocked(), Map.of());
    }

    /** Cooldown-Hinweis als Actionbar - kein Chat-Spam beim wiederholten Klicken. */
    public void sendCooldownActive(Player player, long seconds) {
        Component text = TextUtil.parse(
                configSupplier.get().messages().cooldownActive(),
                Map.of("time", String.valueOf(seconds))
        );
        player.sendActionBar(text);
    }

    /** Blockade durch die Region-/PvP-Guard-Schicht. */
    public void sendRegionBlocked(Player player) {
        send(player, configSupplier.get().regionAwareness().blockedMessage(), Map.of());
    }

    /** MESSAGE-Aktion eines Items: rohe MiniMessage (ohne Prefix), PlaceholderAPI-Kontext = nutzender Spieler. */
    public void sendActionMessage(Player player, String message) {
        player.sendMessage(TextUtil.parse(message, Map.of(), player));
    }

    private void send(CommandSender sender, String body, Map<String, String> placeholders) {
        sender.sendMessage(prefix().append(TextUtil.parse(body, placeholders)));
    }

    private Component prefix() {
        return TextUtil.parse(configSupplier.get().prefix());
    }
}

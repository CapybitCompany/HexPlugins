package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.util.LegacyTextUtil;
import hexcustomitems.util.PlaceholderUtil;
import org.bukkit.command.CommandSender;

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

    public void sendGivenSender(CommandSender sender, String amount, String itemName, String target) {
        send(
                sender,
                configSupplier.get().messages().givenSender(),
                Map.of("amount", amount, "item_name", itemName, "target", target)
        );
    }

    public void sendGivenTarget(CommandSender sender, String amount, String itemName) {
        send(sender, configSupplier.get().messages().givenTarget(), Map.of("amount", amount, "item_name", itemName));
    }

    public void sendList(CommandSender sender, String items) {
        send(sender, configSupplier.get().messages().listHeader(), Map.of("items", items));
    }

    public void sendTargetPlayerRequired(CommandSender sender) {
        send(sender, configSupplier.get().messages().targetPlayerRequired(), Map.of());
    }

    public void sendTargetTooFar(CommandSender sender) {
        send(sender, configSupplier.get().messages().targetTooFar(), Map.of());
    }

    public void sendTargetOpProtected(CommandSender sender) {
        send(sender, configSupplier.get().messages().targetOpProtected(), Map.of());
    }

    public void sendDropBlocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().dropBlocked(), Map.of());
    }

    private void send(CommandSender sender, String body, Map<String, String> placeholders) {
        String prefix = configSupplier.get().prefix();
        String finalText = PlaceholderUtil.apply(prefix + body, placeholders);
        sender.sendMessage(LegacyTextUtil.toComponent(finalText));
    }
}

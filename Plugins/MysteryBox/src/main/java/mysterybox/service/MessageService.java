package mysterybox.service;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.util.LegacyTextUtil;
import mysterybox.util.PlaceholderUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MessageService {

    private final Supplier<MysteryBoxConfig> configSupplier;

    public MessageService(Supplier<MysteryBoxConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void sendNoPermission(CommandSender sender) {
        send(sender, configSupplier.get().messages().noPermission(), Map.of());
    }

    public void sendReloaded(CommandSender sender) {
        send(sender, configSupplier.get().messages().reloaded(), Map.of());
    }

    public void sendPlayerNotFound(CommandSender sender) {
        send(sender, configSupplier.get().messages().playerNotFound(), Map.of());
    }

    public void sendUsageMysteryBox(CommandSender sender) {
        send(sender, configSupplier.get().messages().usageMysteryBox(), Map.of());
    }

    public void sendOnlyPlayer(CommandSender sender) {
        send(sender, configSupplier.get().messages().onlyPlayer(), Map.of());
    }

    public void sendGiveSuccessSender(CommandSender sender, String target, int amount) {
        send(
                sender,
                configSupplier.get().messages().giveSuccessSender(),
                Map.of("target", target, "amount", String.valueOf(amount))
        );
    }

    public void sendGiveSuccessTarget(Player target, int amount) {
        send(target, configSupplier.get().messages().giveSuccessTarget(), Map.of("amount", String.valueOf(amount)));
    }

    public void sendVoucherGiveSuccess(Player player) {
        send(player, configSupplier.get().messages().voucherGiveSuccess(), Map.of());
    }

    public void sendAlreadyOpening(Player player) {
        send(player, configSupplier.get().messages().alreadyOpening(), Map.of());
    }

    public void sendOpenStarted(Player player) {
        send(player, configSupplier.get().messages().openStarted(), Map.of());
    }

    public void sendOpenWon(Player player, String rewardName) {
        send(player, configSupplier.get().messages().openWon(), Map.of("reward_name", rewardName));
    }

    public void sendInventoryFull(Player player) {
        send(player, configSupplier.get().messages().inventoryFull(), Map.of());
    }

    public void sendRaw(CommandSender sender, String rawMessage, Map<String, String> placeholders) {
        send(sender, rawMessage, placeholders);
    }

    public void sendActionbar(Player player, String rawMessage, Map<String, String> placeholders) {
        String message = PlaceholderUtil.apply(rawMessage, placeholders);
        player.sendActionBar(LegacyTextUtil.toComponent(message));
    }

    private void send(CommandSender sender, String body, Map<String, String> placeholders) {
        String prefix = configSupplier.get().prefix();
        String raw = PlaceholderUtil.apply(prefix + body, placeholders);
        sender.sendMessage(LegacyTextUtil.toComponent(raw));
    }
}

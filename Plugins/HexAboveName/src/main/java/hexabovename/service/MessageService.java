package hexabovename.service;

import hexabovename.config.HexAboveNameConfig;
import hexabovename.util.LegacyTextUtil;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.function.Supplier;

public final class MessageService {

    private final Supplier<HexAboveNameConfig> configSupplier;

    public MessageService(Supplier<HexAboveNameConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void sendNoPermission(CommandSender sender) {
        send(sender, configSupplier.get().messages().noPermission());
    }

    public void sendReloaded(CommandSender sender) {
        send(sender, configSupplier.get().messages().reloaded());
    }

    public void sendReloadFailed(CommandSender sender) {
        send(sender, configSupplier.get().messages().reloadFailed());
    }

    public void sendUsage(CommandSender sender, String label) {
        send(sender, configSupplier.get().messages().usage().replace("<label>", label));
    }

    public void sendPlayerNotFound(CommandSender sender, String playerName) {
        send(sender, configSupplier.get().messages().playerNotFound().replace("<player>", playerName));
    }

    public void sendTitleSet(CommandSender sender, String playerName, String title) {
        send(
                sender,
                configSupplier.get().messages().titleSet()
                        .replace("<player>", playerName)
                        .replace("<title>", title)
        );
    }

    public void sendTitleCleared(CommandSender sender, String playerName) {
        send(sender, configSupplier.get().messages().titleCleared().replace("<player>", playerName));
    }

    public void sendTitleTooLong(CommandSender sender, int maxLength) {
        send(sender, configSupplier.get().messages().titleTooLong().replace("<max>", String.valueOf(maxLength)));
    }

    public void sendStorageWriteFailed(CommandSender sender) {
        send(sender, configSupplier.get().messages().storageWriteFailed());
    }

    private void send(CommandSender sender, String message) {
        HexAboveNameConfig config = configSupplier.get();
        sender.sendMessage(LegacyTextUtil.colorize(config.messages().prefix() + message));
    }
}

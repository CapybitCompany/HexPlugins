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

    private void send(CommandSender sender, String message) {
        HexAboveNameConfig config = configSupplier.get();
        sender.sendMessage(LegacyTextUtil.colorize(config.messages().prefix() + message));
    }
}

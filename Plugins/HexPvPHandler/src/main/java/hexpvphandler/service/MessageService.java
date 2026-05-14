package hexpvphandler.service;

import hexpvphandler.config.HexPvPHandlerConfig;
import hexpvphandler.util.LegacyTextUtil;
import org.bukkit.command.CommandSender;

import java.util.Objects;
import java.util.function.Supplier;

public final class MessageService {

    private final Supplier<HexPvPHandlerConfig> configSupplier;

    public MessageService(Supplier<HexPvPHandlerConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void sendNoPermission(CommandSender sender) {
        send(sender, configSupplier.get().messages().noPermission());
    }

    public void sendBlocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().pvpBlocked());
    }

    public void sendUnblocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().pvpUnblocked());
    }

    public void sendAlreadyBlocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().pvpAlreadyBlocked());
    }

    public void sendAlreadyUnblocked(CommandSender sender) {
        send(sender, configSupplier.get().messages().pvpAlreadyUnblocked());
    }

    private void send(CommandSender sender, String message) {
        HexPvPHandlerConfig config = configSupplier.get();
        sender.sendMessage(LegacyTextUtil.colorize(config.messages().prefix() + message));
    }
}

package hexpvphandler.command;

import hexpvphandler.config.HexPvPHandlerConfig;
import hexpvphandler.service.MessageService;
import hexpvphandler.service.PvpToggleService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.Supplier;

public final class UnblockPvpCommand implements CommandExecutor {

    private final Supplier<HexPvPHandlerConfig> configSupplier;
    private final PvpToggleService toggleService;
    private final MessageService messageService;

    public UnblockPvpCommand(
            Supplier<HexPvPHandlerConfig> configSupplier,
            PvpToggleService toggleService,
            MessageService messageService
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.toggleService = Objects.requireNonNull(toggleService, "toggleService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(configSupplier.get().togglePermission())) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (!toggleService.setBlocked(false)) {
            messageService.sendAlreadyUnblocked(sender);
            return true;
        }

        messageService.sendUnblocked(sender);
        return true;
    }
}

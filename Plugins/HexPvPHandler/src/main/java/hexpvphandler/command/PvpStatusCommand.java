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
import java.util.logging.Logger;

public final class PvpStatusCommand implements CommandExecutor {

    private final Supplier<HexPvPHandlerConfig> configSupplier;
    private final PvpToggleService toggleService;
    private final MessageService messageService;
    private final Logger logger;

    public PvpStatusCommand(
            Supplier<HexPvPHandlerConfig> configSupplier,
            PvpToggleService toggleService,
            MessageService messageService,
            Logger logger
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.toggleService = Objects.requireNonNull(toggleService, "toggleService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String actor = sender.getName();
        if (!sender.hasPermission(configSupplier.get().togglePermission())) {
            messageService.sendNoPermission(sender);
            logger.info("Odrzucono /hex_pvpstatus dla '" + actor + "' (brak uprawnień).");
            return true;
        }

        boolean blocked = toggleService.isBlocked();
        messageService.sendStatus(sender, blocked);
        logger.info("Komenda /hex_pvpstatus przez '" + actor + "' (stan PvP: " + (blocked ? "zablokowane" : "odblokowane") + ").");
        return true;
    }
}

package mysterybox.service;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.util.PlaceholderUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class VoucherService {

    private final JavaPlugin plugin;
    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ItemFactoryService itemFactoryService;
    private final MessageService messageService;

    public VoucherService(
            JavaPlugin plugin,
            Supplier<MysteryBoxConfig> configSupplier,
            ItemFactoryService itemFactoryService,
            MessageService messageService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    public void activateVoucher(Player player, EquipmentSlot hand) {
        boolean consumed = itemFactoryService.consumeOneFromHand(player, hand, itemFactoryService::isVipVoucherItem);
        if (!consumed) {
            return;
        }

        MysteryBoxConfig.VoucherSettings voucher = configSupplier.get().voucher();
        boolean alreadyVip = isAlreadyVip(player, voucher);

        if (alreadyVip) {
            executeCommands(voucher.alreadyVip().commands(), player);
            if (!voucher.alreadyVip().message().isBlank()) {
                messageService.sendRaw(player, voucher.alreadyVip().message(), Map.of("player", player.getName()));
            }
            if (!voucher.alreadyVip().actionbar().isBlank()) {
                messageService.sendActionbar(player, voucher.alreadyVip().actionbar(), Map.of("player", player.getName()));
            }
            return;
        }

        executeCommands(voucher.activate().commands(), player);
        if (!voucher.activate().message().isBlank()) {
            messageService.sendRaw(player, voucher.activate().message(), Map.of("player", player.getName()));
        }
        if (!voucher.activate().actionbar().isBlank()) {
            messageService.sendActionbar(player, voucher.activate().actionbar(), Map.of("player", player.getName()));
        }
    }

    private boolean isAlreadyVip(Player player, MysteryBoxConfig.VoucherSettings voucher) {
        if (voucher.treatOpAsAlreadyVip() && player.isOp()) {
            return true;
        }

        for (String permission : voucher.alreadyVipCheckPermissions()) {
            if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private void executeCommands(java.util.List<String> commands, Player player) {
        for (String rawCommand : commands) {
            if (rawCommand == null || rawCommand.isBlank()) {
                continue;
            }
            String command = PlaceholderUtil.apply(rawCommand, Map.of("player", player.getName()));
            Bukkit.dispatchCommand(plugin.getServer().getConsoleSender(), command);
        }
    }
}

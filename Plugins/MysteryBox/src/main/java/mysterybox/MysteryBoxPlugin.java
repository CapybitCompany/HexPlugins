package mysterybox;

import mysterybox.command.MysteryBoxCommand;
import mysterybox.command.MysteryBoxVipCommand;
import mysterybox.config.MysteryBoxConfig;
import mysterybox.config.MysteryBoxConfigLoader;
import mysterybox.gui.MysteryBoxOpeningService;
import mysterybox.listener.MysteryBoxDropListener;
import mysterybox.listener.MysteryBoxGuiListener;
import mysterybox.listener.MysteryBoxInteractListener;
import mysterybox.service.AuditLogService;
import mysterybox.service.ItemFactoryService;
import mysterybox.service.MessageService;
import mysterybox.service.RewardService;
import mysterybox.service.VoucherService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public final class MysteryBoxPlugin extends JavaPlugin {

    private final AtomicReference<MysteryBoxConfig> configRef = new AtomicReference<>();
    private MysteryBoxConfigLoader configLoader;
    private MysteryBoxOpeningService openingService;
    private RewardService rewardService;
    private AuditLogService auditLogService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configLoader = new MysteryBoxConfigLoader(this);
        MysteryBoxConfig loadedConfig = this.configLoader.load();
        this.configRef.set(loadedConfig);

        MessageService messageService = new MessageService(configRef::get);
        ItemFactoryService itemFactoryService = new ItemFactoryService(this, configRef::get);
        this.rewardService = new RewardService(itemFactoryService, getLogger(), loadedConfig);
        this.auditLogService = new AuditLogService(this, configRef::get);
        VoucherService voucherService = new VoucherService(this, configRef::get, itemFactoryService, messageService);
        this.openingService = new MysteryBoxOpeningService(
                this,
                configRef::get,
                itemFactoryService,
                rewardService,
                auditLogService,
                messageService
        );

        if (!registerCommands(messageService, itemFactoryService)) {
            return;
        }
        registerListeners(itemFactoryService, voucherService);

        getLogger().info("MysteryBox uruchomiony.");
    }

    @Override
    public void onDisable() {
        if (openingService != null) {
            openingService.cancelAllAndRefund();
        }
        if (auditLogService != null) {
            auditLogService.shutdown();
        }
        getLogger().info("MysteryBox zatrzymany.");
    }

    private void registerListeners(ItemFactoryService itemFactoryService, VoucherService voucherService) {
        getServer().getPluginManager().registerEvents(
                new MysteryBoxInteractListener(itemFactoryService, openingService, voucherService),
                this
        );
        getServer().getPluginManager().registerEvents(
                new MysteryBoxDropListener(configRef::get, itemFactoryService),
                this
        );
        getServer().getPluginManager().registerEvents(new MysteryBoxGuiListener(openingService), this);
    }

    private boolean registerCommands(MessageService messageService, ItemFactoryService itemFactoryService) {
        PluginCommand mysteryBox = getCommand("mysterybox");
        PluginCommand mysteryBoxVip = getCommand("mysteryboxvip");

        if (mysteryBox == null || mysteryBoxVip == null) {
            getLogger().severe("Brak komend mysterybox/mysteryboxvip w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        MysteryBoxCommand mysteryBoxCommand = new MysteryBoxCommand(
                configRef::get,
                itemFactoryService,
                messageService,
                this::reloadMysteryBoxConfiguration
        );
        mysteryBox.setExecutor(mysteryBoxCommand);
        mysteryBox.setTabCompleter(mysteryBoxCommand);

        mysteryBoxVip.setExecutor(new MysteryBoxVipCommand(configRef::get, itemFactoryService, messageService));
        return true;
    }

    private void reloadMysteryBoxConfiguration() {
        if (openingService != null) {
            openingService.cancelAllAndRefund();
        }

        reloadConfig();
        MysteryBoxConfig reloaded = configLoader.load();
        configRef.set(reloaded);
        if (rewardService != null) {
            rewardService.updateConfig(reloaded);
        }
    }
}

package hexchat;

import hexchat.command.HexChatCommand;
import hexchat.config.HexChatConfig;
import hexchat.config.HexChatConfigLoader;
import hexchat.integration.luckperms.LuckPermsRankResolver;
import hexchat.listener.HexCommandListener;
import hexchat.listener.HexChatListener;
import hexchat.scheduler.AutoMessageScheduler;
import hexchat.service.ChatCooldownService;
import hexchat.service.ChatFormatService;
import hexchat.service.ChatRankResolver;
import hexchat.service.CommandFilterService;
import hexchat.service.GlobalChatMuteService;
import hexchat.service.HelpCommandService;
import hexchat.service.HexChatMessageService;
import hexchat.service.NoopChatRankResolver;
import hexchat.service.TabCompleteFilterService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexChatPlugin extends JavaPlugin {

    private HexChatConfigLoader configLoader;
    private ChatFormatService chatFormatService;
    private ChatCooldownService chatCooldownService;
    private HexChatMessageService messageService;
    private GlobalChatMuteService globalChatMuteService;
    private CommandFilterService commandFilterService;
    private TabCompleteFilterService tabCompleteFilterService;
    private HelpCommandService helpCommandService;
    private AutoMessageScheduler autoMessageScheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configLoader = new HexChatConfigLoader(this);
        HexChatConfig initialConfig = this.configLoader.load();
        this.chatFormatService = new ChatFormatService(initialConfig, getLogger());
        ChatRankResolver rankResolver = createRankResolver(initialConfig);
        this.chatCooldownService = new ChatCooldownService(chatFormatService::currentConfig, rankResolver);
        this.messageService = new HexChatMessageService(chatFormatService::currentConfig, getLogger());
        this.globalChatMuteService = new GlobalChatMuteService(
                chatFormatService::currentConfig,
                initialConfig.chat().globalMute().initiallyMuted()
        );
        this.commandFilterService = new CommandFilterService(initialConfig);
        this.tabCompleteFilterService = new TabCompleteFilterService(initialConfig);
        this.helpCommandService = new HelpCommandService(getServer().getPluginManager(), getLogger(), initialConfig);
        this.autoMessageScheduler = new AutoMessageScheduler(this, messageService, initialConfig);

        registerListeners();
        if (!registerCommands()) {
            return;
        }

        this.autoMessageScheduler.start();
        getLogger().info("HexChat uruchomiony.");
    }

    @Override
    public void onDisable() {
        if (autoMessageScheduler != null) {
            autoMessageScheduler.stop();
        }
        getLogger().info("HexChat zatrzymany.");
    }

    public void reloadHexChatConfiguration() {
        reloadConfig();
        HexChatConfig updatedConfig = this.configLoader.load();
        this.chatFormatService.updateConfig(updatedConfig);
        this.chatCooldownService.updateRankResolver(createRankResolver(updatedConfig));
        this.commandFilterService.updateConfig(updatedConfig);
        this.tabCompleteFilterService.updateConfig(updatedConfig);
        this.helpCommandService.updateConfig(updatedConfig);
        this.autoMessageScheduler.updateConfig(updatedConfig);
        getLogger().info("Konfiguracja HexChat została przeładowana.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new HexChatListener(this, chatFormatService, chatCooldownService, globalChatMuteService, messageService),
                this
        );
        getServer().getPluginManager().registerEvents(
                new HexCommandListener(commandFilterService, tabCompleteFilterService, helpCommandService, messageService),
                this
        );
    }

    private boolean registerCommands() {
        PluginCommand command = getCommand("hexchat");
        if (command == null) {
            getLogger().severe("Brak komendy 'hexchat' w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        HexChatCommand executor = new HexChatCommand(this, messageService);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }

    public boolean setGlobalChatMuted(boolean value) {
        return globalChatMuteService.setMuted(value);
    }

    public boolean toggleGlobalChatMuted() {
        return globalChatMuteService.toggleMuted();
    }

    public boolean isGlobalChatMuted() {
        return globalChatMuteService.isMuted();
    }

    private ChatRankResolver createRankResolver(HexChatConfig config) {
        if (!config.cooldown().useLuckPermsPrimaryGroup()) {
            getLogger().info("Cooldown po rangach LuckPerms jest wyłączony w config.yml.");
            return new NoopChatRankResolver();
        }

        if (getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().warning("LuckPerms nie jest zainstalowany. Używam fallbacku cooldownu.");
            return new NoopChatRankResolver();
        }

        return LuckPermsRankResolver.create()
                .map(resolver -> {
                    getLogger().info("Wykryto LuckPerms: cooldown może korzystać z primary group.");
                    return (ChatRankResolver) resolver;
                })
                .orElseGet(() -> {
                    getLogger().warning("Nie udało się zainicjalizować LuckPerms API. Używam fallbacku cooldownu.");
                    return new NoopChatRankResolver();
                });
    }
}

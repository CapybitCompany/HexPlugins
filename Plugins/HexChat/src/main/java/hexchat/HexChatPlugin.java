package hexchat;

import hexchat.command.HexChatCommand;
import hexchat.config.HexChatConfig;
import hexchat.config.HexChatConfigLoader;
import hexchat.integration.bukkit.BukkitPlayerDirectory;
import hexchat.integration.luckperms.LuckPermsRankResolver;
import hexchat.listener.HexCommandListener;
import hexchat.listener.HexChatListener;
import hexchat.mute.MuteStorage;
import hexchat.mute.YamlMuteStorage;
import hexchat.scheduler.AutoMessageScheduler;
import hexchat.service.ChatConflictGuard;
import hexchat.service.ChatContentFilterService;
import hexchat.service.ChatCooldownService;
import hexchat.service.ChatFormatService;
import hexchat.service.ChatRankResolver;
import hexchat.service.CommandFilterService;
import hexchat.service.GlobalChatMuteService;
import hexchat.service.HelpCommandService;
import hexchat.service.HexChatMessageService;
import hexchat.service.NoopChatRankResolver;
import hexchat.service.PlayerDirectory;
import hexchat.service.PlayerMuteService;
import hexchat.service.TabCompleteFilterService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class HexChatPlugin extends JavaPlugin {

    private HexChatConfigLoader configLoader;
    private ChatFormatService chatFormatService;
    private ChatCooldownService chatCooldownService;
    private HexChatMessageService messageService;
    private GlobalChatMuteService globalChatMuteService;
    private PlayerMuteService playerMuteService;
    private ChatContentFilterService contentFilterService;
    private ChatConflictGuard conflictGuard;
    private PlayerDirectory playerDirectory;
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

        MuteStorage muteStorage = new YamlMuteStorage(new File(getDataFolder(), "mutes.yml"), getLogger());
        this.playerMuteService = new PlayerMuteService(chatFormatService::currentConfig, muteStorage);
        this.contentFilterService = new ChatContentFilterService(initialConfig);
        this.conflictGuard = new ChatConflictGuard(initialConfig);
        this.playerDirectory = new BukkitPlayerDirectory(getServer());

        this.commandFilterService = new CommandFilterService(initialConfig);
        this.tabCompleteFilterService = new TabCompleteFilterService(initialConfig);
        this.helpCommandService = new HelpCommandService(getServer().getPluginManager(), getLogger(), initialConfig);
        this.autoMessageScheduler = new AutoMessageScheduler(this, messageService, initialConfig);

        registerListeners();
        if (!registerCommands()) {
            return;
        }

        detectChatConflicts();
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
        this.contentFilterService.updateConfig(updatedConfig);
        this.conflictGuard.updateConfig(updatedConfig);
        this.commandFilterService.updateConfig(updatedConfig);
        this.tabCompleteFilterService.updateConfig(updatedConfig);
        this.helpCommandService.updateConfig(updatedConfig);
        this.autoMessageScheduler.updateConfig(updatedConfig);
        detectChatConflicts();
        getLogger().info("Konfiguracja HexChat została przeładowana.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new HexChatListener(
                        this,
                        chatFormatService,
                        chatCooldownService,
                        globalChatMuteService,
                        playerMuteService,
                        contentFilterService,
                        conflictGuard,
                        messageService
                ),
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

        HexChatCommand executor = new HexChatCommand(
                this,
                messageService,
                playerMuteService,
                playerDirectory,
                chatFormatService::currentConfig
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        return true;
    }

    /**
     * Wykrywa inne pluginy zarządzające czatem i ostrzega administratora.
     * Ustawia stan guardu, który decyduje, czy HexChat wymusza własny format,
     * czy ustępuje innemu pluginowi (aby uniknąć konfliktów/"Chat Verification Error").
     */
    private void detectChatConflicts() {
        if (!conflictGuard.isEnabled()) {
            conflictGuard.setConflictDetected(false);
            return;
        }

        List<String> chatListenerPlugins = new ArrayList<>();
        for (RegisteredListener registeredListener : AsyncChatEvent.getHandlerList().getRegisteredListeners()) {
            chatListenerPlugins.add(registeredListener.getPlugin().getName());
        }

        List<String> installedPlugins = new ArrayList<>();
        for (Plugin installed : getServer().getPluginManager().getPlugins()) {
            installedPlugins.add(installed.getName());
        }

        ChatConflictGuard.ConflictReport report =
                conflictGuard.analyze(getName(), chatListenerPlugins, installedPlugins);
        // Tylko realne konflikty formatu (znane pluginy czatu) powodują ustąpienie formatu.
        conflictGuard.setConflictDetected(report.hasFormatConflict());

        if (!conflictGuard.shouldWarn()) {
            return;
        }

        if (report.hasFormatConflict()) {
            getLogger().warning("Wykryto konflikty formatowania czatu:");
            for (String conflict : report.formatConflicts()) {
                getLogger().warning(" - " + conflict);
            }
            if (conflictGuard.shouldEnforceFormat()) {
                getLogger().warning("enforce-format=true: HexChat wymusza własny format czatu (priorytet HIGH).");
            } else {
                getLogger().warning("enforce-format=false: HexChat ustępuje formatowania innemu pluginowi czatu "
                        + "(moderacja/filtry nadal działają).");
            }
        }

        for (String warning : report.listenerWarnings()) {
            getLogger().info("[HexChat] " + warning);
        }
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

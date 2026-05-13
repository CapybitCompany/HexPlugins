package hexchat.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class HexChatConfigLoader {

    private static final String DEFAULT_FORMAT =
            "<gray>[<gold>Chat</gold>]</gray> <player><dark_gray>:</dark_gray> <message>";
    private static final String DEFAULT_CHAT_MUTE_BYPASS_PERMISSION = "hexchat.chatmute.bypass";
    private static final boolean DEFAULT_CHAT_MUTE_INITIAL_STATE = false;
    private static final String DEFAULT_PREFIX = "<dark_gray>[<dark_red>HEX</dark_red>]</dark_gray> ";
    private static final String DEFAULT_NO_PERMISSION = "<red>Nie masz uprawnień do tej komendy.</red>";
    private static final String DEFAULT_RELOADED = "<green>Konfiguracja została przeładowana.</green>";
    private static final String DEFAULT_USAGE = "<yellow>Użycie: /hexchat [reload|mute|unmute|togglemute|mutestatus]</yellow>";
    private static final String DEFAULT_COOLDOWN_WAIT = "<gold>Poczekaj jeszcze <white><seconds></white> <gold>sek.</gold>";
    private static final String DEFAULT_CHAT_MUTED = "<red>Czat globalny jest aktualnie wyciszony.</red>";
    private static final String DEFAULT_CHAT_MUTE_ENABLED = "<green>Wyciszyłeś globalny czat.</green>";
    private static final String DEFAULT_CHAT_MUTE_DISABLED = "<green>Przywróciłeś globalny czat.</green>";
    private static final String DEFAULT_CHAT_MUTE_ALREADY_ENABLED = "<yellow>Czat globalny jest już wyciszony.</yellow>";
    private static final String DEFAULT_CHAT_MUTE_ALREADY_DISABLED = "<yellow>Czat globalny nie jest wyciszony.</yellow>";
    private static final String DEFAULT_CHAT_MUTE_STATUS_ENABLED = "<red>Status czatu: WYCISZONY.</red>";
    private static final String DEFAULT_CHAT_MUTE_STATUS_DISABLED = "<green>Status czatu: AKTYWNY.</green>";
    private static final String DEFAULT_COOLDOWN_BYPASS_PERMISSION = "hexchat.cooldown.bypass";
    private static final int DEFAULT_COOLDOWN_SECONDS = 10;
    private static final int DEFAULT_AUTO_MESSAGES_INTERVAL_SECONDS = 180;
    private static final String DEFAULT_COMMAND_FILTER_BYPASS_PERMISSION = "hexchat.commandfilter.bypass";
    private static final String DEFAULT_TAB_COMPLETE_FILTER_BYPASS_PERMISSION = "hexchat.tabcomplete.bypass";
    private static final String DEFAULT_HELP_UNAVAILABLE_MESSAGE = "<red>Tryb Essentials jest aktywny, ale plugin Essentials nie został znaleziony.</red>";
    private static final String DEFAULT_COMMAND_BLOCK_MESSAGE = "<red>Ta komenda jest zablokowana.</red>";
    private static final List<HexChatConfig.GroupCooldown> DEFAULT_RANK_COOLDOWNS = List.of(
            new HexChatConfig.GroupCooldown("Gracz", 10),
            new HexChatConfig.GroupCooldown("VIP", 2),
            new HexChatConfig.GroupCooldown("SVIP", 2),
            new HexChatConfig.GroupCooldown("Elita", 2),
            new HexChatConfig.GroupCooldown("Media", 10),
            new HexChatConfig.GroupCooldown("Admin", 2)
    );
    private static final List<HexChatConfig.PermissionCooldown> DEFAULT_PERMISSION_OVERRIDES = List.of();
    private static final List<String> DEFAULT_AUTO_MESSAGES = List.of(
            "<gray>Odwiedź nasz Discord: <gold>discord.gg/twojserwer</gold></gray>",
            "<gray>Użyj <gold>/help</gold>, aby sprawdzić najważniejsze komendy.</gray>"
    );
    private static final List<String> DEFAULT_ALLOWED_COMMANDS = List.of(
            "helpop",
            "punkty",
            "tablica",
            "lobby",
            "spawn",
            "help"
    );
    private static final List<String> DEFAULT_ALLOWED_NAMESPACED_COMMANDS = List.of(
            "essentials:spawn"
    );
    private static final boolean DEFAULT_HIDE_NAMESPACED_SUGGESTIONS = true;
    private static final List<String> DEFAULT_HIDDEN_COMMANDS = List.of(
            "bukkit:plugins",
            "paper:plugins",
            "minecraft:plugins",
            "minecraft:version",
            "paper:version"
    );
    private static final List<String> DEFAULT_HELP_ALIASES = List.of("help");
    private static final List<String> DEFAULT_HELP_CUSTOM_LINES = List.of(
            "<gold>Najważniejsze komendy:</gold>",
            "<yellow>/spawn</yellow> <gray>- teleport do spawnu</gray>",
            "<yellow>/msg (gracz) (wiadomość)</yellow> <gray>- prywatna wiadomość</gray>"
    );

    private final JavaPlugin plugin;

    public HexChatConfigLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public HexChatConfig load() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        HexChatConfig.Chat chat = loadChat(config, logger);
        HexChatConfig.Cooldown cooldown = loadCooldown(config, logger);
        HexChatConfig.AutoMessages autoMessages = loadAutoMessages(config, logger);
        HexChatConfig.CommandFilter commandFilter = loadCommandFilter(config, logger);
        HexChatConfig.TabCompleteFilter tabCompleteFilter = loadTabCompleteFilter(config, logger);
        HexChatConfig.Help help = loadHelp(config, logger);
        HexChatConfig.Messages messages = loadMessages(config, logger);

        return new HexChatConfig(
                chat,
                cooldown,
                autoMessages,
                commandFilter,
                tabCompleteFilter,
                help,
                messages
        );
    }

    private HexChatConfig.Chat loadChat(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("chat.enabled", true);
        String format = readNonBlank(config, "chat.format", DEFAULT_FORMAT, logger);
        boolean globalMuteEnabled = config.getBoolean("chat.global-mute.enabled", true);
        boolean initiallyMuted = config.getBoolean("chat.global-mute.initially-muted", DEFAULT_CHAT_MUTE_INITIAL_STATE);
        String bypassPermission = readNonBlank(
                config,
                "chat.global-mute.bypass-permission",
                DEFAULT_CHAT_MUTE_BYPASS_PERMISSION,
                logger
        );

        HexChatConfig.GlobalMute globalMute = new HexChatConfig.GlobalMute(globalMuteEnabled, initiallyMuted, bypassPermission);
        return new HexChatConfig.Chat(enabled, format, globalMute);
    }

    private HexChatConfig.Cooldown loadCooldown(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("cooldown.enabled", true);
        String bypassPermission = readNonBlankWithLegacy(
                config,
                "cooldown.bypass-permission",
                "chat.cooldown.bypass",
                DEFAULT_COOLDOWN_BYPASS_PERMISSION,
                logger
        );
        boolean useLuckPermsPrimaryGroup = config.getBoolean("cooldown.use-luckperms-primary-group", true);
        int defaultSeconds = Math.max(0, config.getInt("cooldown.default-seconds", DEFAULT_COOLDOWN_SECONDS));
        List<HexChatConfig.GroupCooldown> rankCooldowns = readRankCooldowns(config, logger);
        List<HexChatConfig.PermissionCooldown> permissionOverrides = readPermissionCooldowns(config, logger);

        return new HexChatConfig.Cooldown(
                enabled,
                bypassPermission,
                useLuckPermsPrimaryGroup,
                defaultSeconds,
                rankCooldowns,
                permissionOverrides
        );
    }

    private HexChatConfig.AutoMessages loadAutoMessages(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("auto-messages.enabled", false);
        int intervalSeconds = Math.max(
                1,
                config.getInt("auto-messages.interval-seconds", DEFAULT_AUTO_MESSAGES_INTERVAL_SECONDS)
        );
        boolean randomOrder = config.getBoolean("auto-messages.random-order", false);
        List<String> messages = readNonBlankStringList(
                config,
                "auto-messages.messages",
                DEFAULT_AUTO_MESSAGES,
                logger
        );

        return new HexChatConfig.AutoMessages(enabled, intervalSeconds, randomOrder, messages);
    }

    private HexChatConfig.CommandFilter loadCommandFilter(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("command-filter.enabled", false);
        String bypassPermission = readNonBlank(
                config,
                "command-filter.bypass-permission",
                DEFAULT_COMMAND_FILTER_BYPASS_PERMISSION,
                logger
        );
        List<String> allowedCommands = readNonBlankStringListWithLegacy(
                config,
                "command-filter.allowed-commands",
                "command-filter.blocked-commands",
                DEFAULT_ALLOWED_COMMANDS,
                logger
        );
        String blockedMessage = readNonBlank(
                config,
                "command-filter.block-message",
                DEFAULT_COMMAND_BLOCK_MESSAGE,
                logger
        );
        boolean hideNamespacedSuggestions = config.getBoolean(
                "command-filter.hide-namespaced-suggestions",
                DEFAULT_HIDE_NAMESPACED_SUGGESTIONS
        );
        List<String> allowedNamespacedSuggestions = readNonBlankStringList(
                config,
                "command-filter.allowed-namespaced-suggestions",
                DEFAULT_ALLOWED_NAMESPACED_COMMANDS,
                logger
        );

        return new HexChatConfig.CommandFilter(
                enabled,
                bypassPermission,
                allowedCommands,
                blockedMessage,
                hideNamespacedSuggestions,
                allowedNamespacedSuggestions
        );
    }

    private HexChatConfig.TabCompleteFilter loadTabCompleteFilter(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("tab-complete-filter.enabled", false);
        String bypassPermission = readNonBlank(
                config,
                "tab-complete-filter.bypass-permission",
                DEFAULT_TAB_COMPLETE_FILTER_BYPASS_PERMISSION,
                logger
        );
        List<String> hiddenCommands = readNonBlankStringList(
                config,
                "tab-complete-filter.hidden-commands",
                DEFAULT_HIDDEN_COMMANDS,
                logger
        );

        return new HexChatConfig.TabCompleteFilter(enabled, bypassPermission, hiddenCommands);
    }

    private HexChatConfig.Help loadHelp(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("help.enabled", false);
        HexChatConfig.Help.Mode mode = readHelpMode(config, logger);
        List<String> aliases = readNonBlankStringList(config, "help.command-aliases", DEFAULT_HELP_ALIASES, logger);
        List<String> customLines = readNonBlankStringList(config, "help.custom-lines", DEFAULT_HELP_CUSTOM_LINES, logger);
        boolean fallbackToCustom = config.getBoolean("help.fallback-to-custom-when-essentials-missing", true);
        String unavailableMessage = readNonBlank(
                config,
                "help.unavailable-message",
                DEFAULT_HELP_UNAVAILABLE_MESSAGE,
                logger
        );

        return new HexChatConfig.Help(
                enabled,
                mode,
                aliases,
                customLines,
                fallbackToCustom,
                unavailableMessage
        );
    }

    private HexChatConfig.Messages loadMessages(FileConfiguration config, Logger logger) {
        String prefix = readNonBlank(config, "messages.prefix", DEFAULT_PREFIX, logger);
        String noPermission = readNonBlank(config, "messages.no-permission", DEFAULT_NO_PERMISSION, logger);
        String reloaded = readNonBlank(config, "messages.reloaded", DEFAULT_RELOADED, logger);
        String usage = readNonBlank(config, "messages.usage", DEFAULT_USAGE, logger);
        String cooldownWait = readNonBlank(config, "messages.cooldown-wait", DEFAULT_COOLDOWN_WAIT, logger);
        String chatMuted = readNonBlank(config, "messages.chat-muted", DEFAULT_CHAT_MUTED, logger);
        String chatMuteEnabled = readNonBlank(config, "messages.chat-mute-enabled", DEFAULT_CHAT_MUTE_ENABLED, logger);
        String chatMuteDisabled = readNonBlank(config, "messages.chat-mute-disabled", DEFAULT_CHAT_MUTE_DISABLED, logger);
        String chatMuteAlreadyEnabled = readNonBlank(
                config,
                "messages.chat-mute-already-enabled",
                DEFAULT_CHAT_MUTE_ALREADY_ENABLED,
                logger
        );
        String chatMuteAlreadyDisabled = readNonBlank(
                config,
                "messages.chat-mute-already-disabled",
                DEFAULT_CHAT_MUTE_ALREADY_DISABLED,
                logger
        );
        String chatMuteStatusEnabled = readNonBlank(
                config,
                "messages.chat-mute-status-enabled",
                DEFAULT_CHAT_MUTE_STATUS_ENABLED,
                logger
        );
        String chatMuteStatusDisabled = readNonBlank(
                config,
                "messages.chat-mute-status-disabled",
                DEFAULT_CHAT_MUTE_STATUS_DISABLED,
                logger
        );

        return new HexChatConfig.Messages(
                prefix,
                noPermission,
                reloaded,
                usage,
                cooldownWait,
                chatMuted,
                chatMuteEnabled,
                chatMuteDisabled,
                chatMuteAlreadyEnabled,
                chatMuteAlreadyDisabled,
                chatMuteStatusEnabled,
                chatMuteStatusDisabled
        );
    }

    private List<HexChatConfig.GroupCooldown> readRankCooldowns(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> rawOverrides = config.getMapList("cooldown.rank-cooldowns");
        if (rawOverrides.isEmpty()) {
            return DEFAULT_RANK_COOLDOWNS;
        }

        List<HexChatConfig.GroupCooldown> parsed = new ArrayList<>();
        for (Map<?, ?> rawOverride : rawOverrides) {
            Object rankRaw = rawOverride.get("rank");
            Object secondsRaw = rawOverride.get("seconds");

            if (!(rankRaw instanceof String rank) || rank.isBlank()) {
                logger.warning("Niepoprawny wpis cooldown.rank-cooldowns: brak pola 'rank'.");
                continue;
            }
            if (!(secondsRaw instanceof Number secondsNumber)) {
                logger.warning("Niepoprawny wpis cooldown.rank-cooldowns dla rangi '" + rank + "': brak liczby w polu 'seconds'.");
                continue;
            }

            int seconds = Math.max(0, secondsNumber.intValue());
            parsed.add(new HexChatConfig.GroupCooldown(rank, seconds));
        }

        if (parsed.isEmpty()) {
            logger.warning("Brak poprawnych wpisów cooldown.rank-cooldowns. Używam domyślnej mapy rang.");
            return DEFAULT_RANK_COOLDOWNS;
        }

        return parsed;
    }

    private List<HexChatConfig.PermissionCooldown> readPermissionCooldowns(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> rawOverrides = config.getMapList("cooldown.permission-overrides");
        if (rawOverrides.isEmpty()) {
            return DEFAULT_PERMISSION_OVERRIDES;
        }

        List<HexChatConfig.PermissionCooldown> parsed = new ArrayList<>();
        for (Map<?, ?> rawOverride : rawOverrides) {
            Object permissionRaw = rawOverride.get("permission");
            Object secondsRaw = rawOverride.get("seconds");

            if (!(permissionRaw instanceof String permission) || permission.isBlank()) {
                logger.warning("Niepoprawny wpis cooldown.permission-overrides: brak pola 'permission'.");
                continue;
            }
            if (!(secondsRaw instanceof Number secondsNumber)) {
                logger.warning("Niepoprawny wpis cooldown.permission-overrides dla '" + permission + "': brak liczby w polu 'seconds'.");
                continue;
            }

            int seconds = Math.max(0, secondsNumber.intValue());
            parsed.add(new HexChatConfig.PermissionCooldown(permission, seconds));
        }

        if (parsed.isEmpty()) {
            logger.warning("Brak poprawnych wpisów cooldown.permission-overrides. Używam domyślnej listy.");
            return DEFAULT_PERMISSION_OVERRIDES;
        }

        return parsed;
    }

    private HexChatConfig.Help.Mode readHelpMode(FileConfiguration config, Logger logger) {
        String rawMode = config.getString("help.mode", HexChatConfig.Help.Mode.CUSTOM.name());
        if (rawMode == null || rawMode.isBlank()) {
            logger.warning("Brak wartości 'help.mode' w config.yml. Używam trybu CUSTOM.");
            return HexChatConfig.Help.Mode.CUSTOM;
        }

        try {
            return HexChatConfig.Help.Mode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Niepoprawna wartość 'help.mode': '" + rawMode + "'. Używam trybu CUSTOM.");
            return HexChatConfig.Help.Mode.CUSTOM;
        }
    }

    private List<String> readNonBlankStringList(
            FileConfiguration config,
            String path,
            List<String> fallback,
            Logger logger
    ) {
        List<String> rawValues = config.getStringList(path);
        if (rawValues.isEmpty()) {
            return fallback;
        }

        List<String> parsed = new ArrayList<>();
        for (String rawValue : rawValues) {
            if (rawValue == null) {
                continue;
            }

            String value = rawValue.trim();
            if (!value.isBlank()) {
                parsed.add(value);
            }
        }

        if (parsed.isEmpty()) {
            logger.warning("Brak poprawnych wpisów '" + path + "' w config.yml. Używam wartości domyślnej.");
            return fallback;
        }

        return List.copyOf(parsed);
    }

    private List<String> readNonBlankStringListWithLegacy(
            FileConfiguration config,
            String path,
            String legacyPath,
            List<String> fallback,
            Logger logger
    ) {
        List<String> values = readNonBlankStringList(config, path, List.of(), logger);
        if (!values.isEmpty()) {
            return values;
        }

        List<String> legacyValues = readNonBlankStringList(config, legacyPath, List.of(), logger);
        if (!legacyValues.isEmpty()) {
            logger.warning("Wykryto legacy klucz '" + legacyPath + "'. Zmień na '" + path + "'.");
            return legacyValues;
        }

        logger.warning("Brak poprawnych wpisów '" + path + "' w config.yml. Używam wartości domyślnej.");
        return fallback;
    }

    private String readNonBlank(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            logger.warning("Brak lub pusta wartość '" + path + "' w config.yml. Używam wartości domyślnej.");
            return fallback;
        }
        return value.trim();
    }

    private String readNonBlankWithLegacy(
            FileConfiguration config,
            String path,
            String legacyPath,
            String fallback,
            Logger logger
    ) {
        String value = config.getString(path);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }

        String legacyValue = config.getString(legacyPath);
        if (legacyValue != null && !legacyValue.isBlank()) {
            logger.warning("Wykryto legacy klucz '" + legacyPath + "'. Zmień na '" + path + "'.");
            return legacyValue.trim();
        }

        logger.warning("Brak lub pusta wartość '" + path + "' w config.yml. Używam wartości domyślnej.");
        return fallback;
    }
}

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
    private static final String DEFAULT_CONTENT_FILTER_BYPASS_PERMISSION = "hexchat.filter.bypass";
    private static final String DEFAULT_PLAYER_MUTE_BYPASS_PERMISSION = "hexchat.mute.bypass";
    private static final String DEFAULT_CENSOR_MASK = "***";
    private static final String DEFAULT_AD_BLOCK_MESSAGE = "<red>Reklamy i linki są zabronione.</red>";
    private static final String DEFAULT_BLACKLIST_BLOCK_MESSAGE = "<red>Twoja wiadomość zawiera niedozwolone słowa.</red>";
    private static final String DEFAULT_SPAM_BLOCK_MESSAGE = "<red>Nie spamuj na czacie.</red>";
    private static final String DEFAULT_MUTE_REASON = "Naruszenie regulaminu czatu.";
    private static final List<String> DEFAULT_ALLOWED_DOMAINS = List.of("twojserwer.pl");
    private static final List<String> DEFAULT_KNOWN_CHAT_PLUGINS = List.of(
            "EssentialsXChat",
            "VentureChat",
            "ChatControl",
            "ChatControlRed",
            "DeluxeChat",
            "TownyChat",
            "Chatty",
            "CMI",
            "AdvancedChat"
    );
    private static final String DEFAULT_PRIVATE_MUTED = "<red>Jesteś wyciszony (<time>). Powód: <reason></red>";
    private static final String DEFAULT_MUTE_TIME_PERMANENT = "na zawsze";
    private static final String DEFAULT_PLAYER_MUTE_SET = "<green>Wyciszono gracza <white><player></white> na <white><time></white>. Powód: <reason></green>";
    private static final String DEFAULT_PLAYER_MUTE_REMOVED = "<green>Zdjęto wyciszenie z gracza <white><player></white>.</green>";
    private static final String DEFAULT_PLAYER_MUTE_NOT_MUTED = "<yellow>Gracz <white><player></white> nie jest wyciszony.</yellow>";
    private static final String DEFAULT_PLAYER_MUTE_TARGET_NOT_FOUND = "<red>Nie znaleziono gracza <white><player></white>.</red>";
    private static final String DEFAULT_PLAYER_MUTE_INFO = "<gold>Gracz <white><player></white> jest wyciszony (<time>). Powód: <reason></gold>";
    private static final String DEFAULT_PLAYER_MUTE_DURATION_INVALID = "<red>Niepoprawny czas trwania: <white><input></white>. Przykłady: 30m, 2h, 7d, perm.</red>";
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
        HexChatConfig.ContentFilter contentFilter = loadContentFilter(config, logger);
        HexChatConfig.PlayerMute playerMute = loadPlayerMute(config, logger);
        HexChatConfig.AutoMessages autoMessages = loadAutoMessages(config, logger);
        HexChatConfig.CommandFilter commandFilter = loadCommandFilter(config, logger);
        HexChatConfig.TabCompleteFilter tabCompleteFilter = loadTabCompleteFilter(config, logger);
        HexChatConfig.Help help = loadHelp(config, logger);
        HexChatConfig.Messages messages = loadMessages(config, logger);

        return new HexChatConfig(
                chat,
                cooldown,
                contentFilter,
                playerMute,
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
        HexChatConfig.ConflictGuard conflictGuard = loadConflictGuard(config, logger);
        return new HexChatConfig.Chat(enabled, format, globalMute, conflictGuard);
    }

    private HexChatConfig.ConflictGuard loadConflictGuard(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("chat.conflict-guard.enabled", true);
        boolean warnOnConflict = config.getBoolean("chat.conflict-guard.warn-on-conflict", true);
        boolean enforceFormat = config.getBoolean("chat.conflict-guard.enforce-format", false);
        List<String> knownChatPlugins = readNonBlankStringList(
                config,
                "chat.conflict-guard.known-chat-plugins",
                DEFAULT_KNOWN_CHAT_PLUGINS,
                logger
        );
        return new HexChatConfig.ConflictGuard(enabled, warnOnConflict, enforceFormat, knownChatPlugins);
    }

    private HexChatConfig.ContentFilter loadContentFilter(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("content-filter.enabled", true);
        String bypassPermission = readNonBlank(
                config,
                "content-filter.bypass-permission",
                DEFAULT_CONTENT_FILTER_BYPASS_PERMISSION,
                logger
        );
        String censorMask = readNonBlank(config, "content-filter.censor-mask", DEFAULT_CENSOR_MASK, logger);

        HexChatConfig.AntiAdvertising antiAdvertising = new HexChatConfig.AntiAdvertising(
                config.getBoolean("content-filter.anti-advertising.enabled", true),
                readFilterAction(config, "content-filter.anti-advertising.action", logger),
                readNonBlank(config, "content-filter.anti-advertising.block-message", DEFAULT_AD_BLOCK_MESSAGE, logger),
                readNonBlankStringList(config, "content-filter.anti-advertising.allowed-domains", DEFAULT_ALLOWED_DOMAINS, logger),
                config.getStringList("content-filter.anti-advertising.extra-patterns")
        );

        // Brakujące przełączniki blacklisty domyślnie włączone — starsze configi
        // automatycznie zyskują utwardzone dopasowanie bez ręcznej migracji.
        HexChatConfig.Blacklist blacklist = new HexChatConfig.Blacklist(
                config.getBoolean("content-filter.blacklist.enabled", true),
                readFilterAction(config, "content-filter.blacklist.action", logger),
                readNonBlank(config, "content-filter.blacklist.block-message", DEFAULT_BLACKLIST_BLOCK_MESSAGE, logger),
                config.getBoolean("content-filter.blacklist.match-leetspeak", true),
                config.getBoolean("content-filter.blacklist.ignore-separators", true),
                config.getBoolean("content-filter.blacklist.match-word-endings", true),
                config.getStringList("content-filter.blacklist.words")
        );

        HexChatConfig.AntiSpam antiSpam = new HexChatConfig.AntiSpam(
                config.getBoolean("content-filter.anti-spam.enabled", true),
                readNonBlank(config, "content-filter.anti-spam.block-message", DEFAULT_SPAM_BLOCK_MESSAGE, logger),
                config.getInt("content-filter.anti-spam.max-repeated-messages", 3),
                config.getInt("content-filter.anti-spam.max-caps-percentage", 70),
                config.getInt("content-filter.anti-spam.min-length-for-caps-check", 8)
        );

        return new HexChatConfig.ContentFilter(enabled, bypassPermission, censorMask, antiAdvertising, blacklist, antiSpam);
    }

    private HexChatConfig.PlayerMute loadPlayerMute(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("player-mute.enabled", true);
        String bypassPermission = readNonBlank(
                config,
                "player-mute.bypass-permission",
                DEFAULT_PLAYER_MUTE_BYPASS_PERMISSION,
                logger
        );
        String defaultReason = readNonBlank(config, "player-mute.default-reason", DEFAULT_MUTE_REASON, logger);
        return new HexChatConfig.PlayerMute(enabled, bypassPermission, defaultReason);
    }

    private HexChatConfig.FilterAction readFilterAction(FileConfiguration config, String path, Logger logger) {
        String raw = config.getString(path, HexChatConfig.FilterAction.BLOCK.name());
        if (raw == null || raw.isBlank()) {
            return HexChatConfig.FilterAction.BLOCK;
        }
        try {
            return HexChatConfig.FilterAction.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Niepoprawna wartość '" + path + "': '" + raw + "'. Używam BLOCK.");
            return HexChatConfig.FilterAction.BLOCK;
        }
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
        String privateMuted = readNonBlank(config, "messages.private-muted", DEFAULT_PRIVATE_MUTED, logger);
        // Zgodność wsteczna: starsze configi nie mają 'messages.player-mute-notification',
        // więc natychmiastowe powiadomienie gracza używa wtedy tekstu 'messages.private-muted'.
        String playerMuteNotification = readOptionalNonBlank(
                config,
                "messages.player-mute-notification",
                privateMuted
        );
        String muteTimePermanent = readOptionalNonBlank(
                config,
                "messages.mute-time-permanent",
                DEFAULT_MUTE_TIME_PERMANENT
        );
        String playerMuteSet = readNonBlank(config, "messages.player-mute-set", DEFAULT_PLAYER_MUTE_SET, logger);
        String playerMuteRemoved = readNonBlank(config, "messages.player-mute-removed", DEFAULT_PLAYER_MUTE_REMOVED, logger);
        String playerMuteNotMuted = readNonBlank(config, "messages.player-mute-not-muted", DEFAULT_PLAYER_MUTE_NOT_MUTED, logger);
        String playerMuteTargetNotFound = readNonBlank(
                config,
                "messages.player-mute-target-not-found",
                DEFAULT_PLAYER_MUTE_TARGET_NOT_FOUND,
                logger
        );
        String playerMuteInfo = readNonBlank(config, "messages.player-mute-info", DEFAULT_PLAYER_MUTE_INFO, logger);
        String playerMuteDurationInvalid = readNonBlank(
                config,
                "messages.player-mute-duration-invalid",
                DEFAULT_PLAYER_MUTE_DURATION_INVALID,
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
                chatMuteStatusDisabled,
                privateMuted,
                playerMuteNotification,
                playerMuteSet,
                playerMuteRemoved,
                playerMuteNotMuted,
                playerMuteTargetNotFound,
                playerMuteInfo,
                playerMuteDurationInvalid,
                muteTimePermanent
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

        List<String> parsed = parseNonBlankList(rawValues);
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
        // Odczyt surowy bez logowania: dla nowoczesnych configów z poprawną listą
        // nie generujemy żadnych ostrzeżeń (regresja z wcześniejszego "czytaj z pustym fallbackiem").
        List<String> values = parseNonBlankList(config.getStringList(path));
        if (!values.isEmpty()) {
            return List.copyOf(values);
        }

        List<String> legacyValues = parseNonBlankList(config.getStringList(legacyPath));
        if (!legacyValues.isEmpty()) {
            logger.warning("Wykryto legacy klucz '" + legacyPath + "'. Zmień na '" + path + "'.");
            return List.copyOf(legacyValues);
        }

        logger.warning("Brak poprawnych wpisów '" + path + "' w config.yml. Używam wartości domyślnej.");
        return fallback;
    }

    private List<String> parseNonBlankList(List<String> rawValues) {
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
        return parsed;
    }

    /**
     * Odczyt klucza opcjonalnego: brak wartości jest normalną sytuacją (starszy config),
     * więc nie generujemy ostrzeżenia — po cichu używamy podanej wartości zastępczej.
     * <p>
     * {@code contains(path, true)} pomija defaults, które Bukkit dokłada z wbudowanego
     * config.yml. Bez tego nowy klucz "istniałby" w każdej konfiguracji i zadeklarowana
     * zgodność wsteczna (przejęcie wartości ze starszego klucza) nigdy by nie zadziałała.
     * Konfiguracji użytkownika nie zapisujemy ani nie nadpisujemy.
     */
    private String readOptionalNonBlank(FileConfiguration config, String path, String fallback) {
        if (!config.contains(path, true)) {
            return fallback;
        }
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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

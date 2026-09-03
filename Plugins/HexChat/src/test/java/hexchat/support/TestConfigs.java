package hexchat.support;

import hexchat.config.HexChatConfig;

import java.util.List;

/**
 * Fabryki gotowych obiektów konfiguracji na potrzeby testów jednostkowych serwisów.
 * Każda metoda zwraca kompletny, poprawny {@link HexChatConfig}, w którym tylko
 * wskazana sekcja jest dostosowana pod dany test, a pozostałe mają sensowne wartości domyślne.
 */
public final class TestConfigs {

    public static final String DEFAULT_FORMAT =
            "<gray>[<gold>Chat</gold>]</gray> <player><dark_gray>:</dark_gray> <message>";

    private TestConfigs() {
    }

    public static HexChatConfig.Messages messages() {
        return new HexChatConfig.Messages(
                "<prefix> ",
                "no-permission",
                "reloaded",
                "usage",
                "Poczekaj <seconds> sek.",
                "chat-muted",
                "chat-mute-enabled",
                "chat-mute-disabled",
                "chat-mute-already-enabled",
                "chat-mute-already-disabled",
                "chat-mute-status-enabled",
                "chat-mute-status-disabled",
                "private-muted <time> <reason>",
                "mute-notification <player> <time> <reason>",
                "mute-set <player> <time> <reason>",
                "mute-removed <player>",
                "mute-not-muted <player>",
                "mute-target-not-found <player>",
                "mute-info <player> <time> <reason>",
                "mute-duration-invalid <input>",
                "na zawsze"
        );
    }

    public static HexChatConfig.ConflictGuard conflictGuard() {
        return new HexChatConfig.ConflictGuard(true, true, false, List.of());
    }

    public static HexChatConfig.Chat chat() {
        return chat(true, DEFAULT_FORMAT, globalMute(true, false));
    }

    public static HexChatConfig.Chat chat(boolean enabled, String format, HexChatConfig.GlobalMute mute) {
        return new HexChatConfig.Chat(enabled, format, mute, conflictGuard());
    }

    public static HexChatConfig.Chat chatWithGuard(HexChatConfig.ConflictGuard guard) {
        return new HexChatConfig.Chat(true, DEFAULT_FORMAT, globalMute(true, false), guard);
    }

    public static HexChatConfig.GlobalMute globalMute(boolean enabled, boolean initiallyMuted) {
        return new HexChatConfig.GlobalMute(enabled, initiallyMuted, "hexchat.chatmute.bypass");
    }

    public static HexChatConfig.Cooldown cooldown() {
        return new HexChatConfig.Cooldown(
                true,
                "hexchat.cooldown.bypass",
                true,
                10,
                List.of(new HexChatConfig.GroupCooldown("VIP", 2)),
                List.of()
        );
    }

    public static HexChatConfig.ContentFilter contentFilter() {
        return new HexChatConfig.ContentFilter(
                true,
                "hexchat.filter.bypass",
                "***",
                new HexChatConfig.AntiAdvertising(true, HexChatConfig.FilterAction.BLOCK, "<red>ad</red>", List.of(), List.of()),
                new HexChatConfig.Blacklist(
                        true, HexChatConfig.FilterAction.BLOCK, "<red>bl</red>", true, true, true, List.of()
                ),
                new HexChatConfig.AntiSpam(true, "<red>spam</red>", 3, 70, 8)
        );
    }

    public static HexChatConfig.PlayerMute playerMute() {
        return new HexChatConfig.PlayerMute(true, "hexchat.mute.bypass", "Powód domyślny.");
    }

    public static HexChatConfig.AutoMessages autoMessages() {
        return new HexChatConfig.AutoMessages(false, 180, false, List.of("wiadomość"));
    }

    public static HexChatConfig.CommandFilter commandFilter() {
        return new HexChatConfig.CommandFilter(
                false,
                "hexchat.commandfilter.bypass",
                List.of("help", "spawn"),
                "<red>blocked</red>",
                true,
                List.of("essentials:spawn")
        );
    }

    public static HexChatConfig.TabCompleteFilter tabCompleteFilter() {
        return new HexChatConfig.TabCompleteFilter(
                false,
                "hexchat.tabcomplete.bypass",
                List.of("plugins", "version")
        );
    }

    public static HexChatConfig.Help help() {
        return new HexChatConfig.Help(
                false,
                HexChatConfig.Help.Mode.CUSTOM,
                List.of("help"),
                List.of("<gold>linia 1</gold>", "<yellow>linia 2</yellow>"),
                true,
                "<red>unavailable</red>"
        );
    }

    public static HexChatConfig withMessages(HexChatConfig.Messages messages) {
        return new HexChatConfig(
                chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help(), messages
        );
    }

    public static HexChatConfig config() {
        return build(chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withChat(HexChatConfig.Chat chat) {
        return build(chat, cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withCooldown(HexChatConfig.Cooldown cooldown) {
        return build(chat(), cooldown, contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withContentFilter(HexChatConfig.ContentFilter contentFilter) {
        return build(chat(), cooldown(), contentFilter, playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withPlayerMute(HexChatConfig.PlayerMute playerMute) {
        return build(chat(), cooldown(), contentFilter(), playerMute,
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withConflictGuard(HexChatConfig.ConflictGuard guard) {
        return build(chatWithGuard(guard), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help());
    }

    public static HexChatConfig withCommandFilter(HexChatConfig.CommandFilter commandFilter) {
        return build(chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter, tabCompleteFilter(), help());
    }

    public static HexChatConfig withTabCompleteFilter(HexChatConfig.TabCompleteFilter tabCompleteFilter) {
        return build(chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter, help());
    }

    public static HexChatConfig withHelp(HexChatConfig.Help help) {
        return build(chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages(), commandFilter(), tabCompleteFilter(), help);
    }

    public static HexChatConfig withAutoMessages(HexChatConfig.AutoMessages autoMessages) {
        return build(chat(), cooldown(), contentFilter(), playerMute(),
                autoMessages, commandFilter(), tabCompleteFilter(), help());
    }

    private static HexChatConfig build(
            HexChatConfig.Chat chat,
            HexChatConfig.Cooldown cooldown,
            HexChatConfig.ContentFilter contentFilter,
            HexChatConfig.PlayerMute playerMute,
            HexChatConfig.AutoMessages autoMessages,
            HexChatConfig.CommandFilter commandFilter,
            HexChatConfig.TabCompleteFilter tabCompleteFilter,
            HexChatConfig.Help help
    ) {
        return new HexChatConfig(
                chat, cooldown, contentFilter, playerMute,
                autoMessages, commandFilter, tabCompleteFilter, help, messages()
        );
    }
}

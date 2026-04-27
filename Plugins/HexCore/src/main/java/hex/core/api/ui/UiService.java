package hex.core.api.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized UI rendering service based on templates and MiniMessage.
 * Ensures consistent formatting across all plugins.
 */
public interface UiService {

    /**
     * Registers namespace defaults (e.g. namespace=elimination, key=kill.announce).
     * Full key becomes namespace.key unless key already contains a dot.
     */
    void registerDefaults(String namespace, Map<String, String> templates);

    /** Registers namespace defaults with explicit positional args metadata. */
    void registerDefaultsWithArgs(String namespace, Map<String, TemplateDefinition> templates);

    /** Registers a reusable preset made from multiple UI actions. */
    void registerPreset(String presetId, UiPreset preset);

    /** Renders a template into a Component without placeholders. */
    Component render(String templateKey);

    /** Renders a template with placeholder tokens. */
    Component render(String templateKey, UiTokens tokens);

    /**
     * Renders a template with positional arguments declared in ui.yml.
     * For convenience, extra arguments are merged into the last declared token.
     */
    Component render(String templateKey, String... args);

    /** Sends rendered template to a command sender. */
    void send(CommandSender sender, String templateKey);

    void send(CommandSender sender, String templateKey, UiTokens tokens);

    void send(CommandSender sender, String templateKey, String... args);

    /** Broadcasts rendered template to all players. */
    void broadcast(String templateKey);

    void broadcast(String templateKey, UiTokens tokens);

    void broadcast(String templateKey, String... args);

    void sendActionBar(Player player, String templateKey);

    void sendActionBar(Player player, String templateKey, UiTokens tokens);

    void broadcastActionBar(String templateKey, UiTokens tokens);

    void sendTitle(Player player, String titleKey, String subtitleKey, UiTokens tokens);

    void sendTitle(Player player, String titleKey, String subtitleKey, UiTokens tokens,
                   Duration fadeIn, Duration stay, Duration fadeOut);

    void broadcastTitle(String titleKey, String subtitleKey, UiTokens tokens);

    void playSound(Player player, Sound sound);

    void broadcastSound(Sound sound);

    void sendPreset(Player target, String presetId, UiTokens tokens);

    void broadcastPreset(String presetId, UiTokens tokens);

    /** Declared positional argument names for a template. */
    List<String> expectedArgs(String templateKey);

    /** All known template keys loaded from config or registered at runtime. */
    Set<String> templates();

    /** Known template keys for a namespace prefix. */
    Set<String> templates(String namespace);
}

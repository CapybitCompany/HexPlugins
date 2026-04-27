package hex.core.api.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Set;

/**
 * Centralized UI rendering service based on templates and MiniMessage.
 * Ensures consistent formatting across all plugins.
 */
public interface UiService {

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

    /** Declared positional argument names for a template. */
    List<String> expectedArgs(String templateKey);

    /** All known template keys loaded from config. */
    Set<String> templates();
}

package hexpvpsmp.combat;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.ui.MessageService;
import hexpvpsmp.util.LegacyFormat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Objects;

/**
 * Blocks combat-tagged players from running non-whitelisted commands.
 *
 * <p>Trust boundary: this gates {@link PlayerCommandPreprocessEvent}, which
 * fires for player-typed commands and for {@code player.performCommand(...)}.
 * HexNPC {@code player-command} actions therefore ARE subject to the whitelist
 * while the player is tagged — intended, so an NPC cannot be used to escape
 * combat (e.g. an NPC {@code /spawn} button). NPC actions that must always run
 * should use console/op-dispatched commands instead of {@code performCommand},
 * or the player needs {@code hexpvpsmp.bypass}. If NPC actions ever need to be
 * fully trusted, expose {@code CombatConfig#isCommandAllowed} as a shared gate
 * rather than special-casing here.
 */
public final class CombatCommandListener implements Listener {

    private final HexPvpSmpPlugin plugin;

    public CombatCommandListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        if (PermissionGate.bypasses(event.getPlayer())) {
            return;
        }
        if (!plugin.combatTagService().isTagged(event.getPlayer())) {
            return;
        }

        String label = normalizeLabel(event.getMessage());
        if (label.isEmpty()) {
            return;
        }
        if (isAllowed(config, label)) {
            return;
        }

        event.setCancelled(true);
        MessageService messages = plugin.messageService();
        String text = LegacyFormat.replace(config.messages().commandBlocked(), "<command>", label);
        messages.sendChat(event.getPlayer(), text);
    }

    private boolean isAllowed(HexPvpConfig config, String label) {
        if (config.combat().isCommandAllowed(label)) {
            return true;
        }
        // Resolve aliases against the canonical command name.
        CommandMap commandMap = plugin.getServer().getCommandMap();
        Command command = commandMap.getCommand(label);
        if (command == null) {
            return false;
        }
        return config.combat().isCommandAllowed(command.getName());
    }

    private static String normalizeLabel(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String trimmed = message.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        String first = space < 0 ? trimmed : trimmed.substring(0, space);
        // strip plugin namespace prefix like "minecraft:say"
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        return first.toLowerCase(Locale.ROOT);
    }
}

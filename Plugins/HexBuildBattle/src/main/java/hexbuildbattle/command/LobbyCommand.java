package hexbuildbattle.command;

import hexbuildbattle.config.MessageService;
import hexbuildbattle.game.GameManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class LobbyCommand implements CommandExecutor {

    private final GameManager gameManager;
    private final MessageService messages;

    public LobbyCommand(GameManager gameManager, MessageService messages) {
        this.gameManager = gameManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendWithPrefix(sender, "lobby.only-player", Map.of());
            return true;
        }
        if (!player.hasPermission("hexbuildbattle.lobby")) {
            messages.sendWithPrefix(player, "lobby.no-permission", Map.of());
            return true;
        }

        gameManager.handleLobbyCommand(player);
        return true;
    }
}

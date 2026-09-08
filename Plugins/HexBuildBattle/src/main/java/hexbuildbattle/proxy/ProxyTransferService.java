package hexbuildbattle.proxy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.Supplier;

public final class ProxyTransferService {

    private static final String BUNGEE_CHANNEL = "BungeeCord";

    private final JavaPlugin plugin;
    private final Supplier<String> lobbyServerSupplier;
    private boolean registered;

    public ProxyTransferService(JavaPlugin plugin, Supplier<String> lobbyServerSupplier) {
        this.plugin = plugin;
        this.lobbyServerSupplier = lobbyServerSupplier;
    }

    public void register() {
        if (registered) {
            return;
        }
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        registered = true;
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        registered = false;
    }

    public boolean sendToLobby(Player player) {
        String serverName = lobbyServerSupplier.get();
        if (serverName == null || serverName.isBlank()) {
            plugin.getLogger().warning("network.lobby-server is empty; cannot transfer " + player.getName());
            return false;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to create proxy transfer message: " + ex.getMessage());
            return false;
        }
    }
}

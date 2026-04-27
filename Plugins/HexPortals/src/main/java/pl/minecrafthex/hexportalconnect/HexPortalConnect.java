package pl.minecrafthex.hexportalconnect;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HexPortalConnect extends JavaPlugin implements Listener {

    private final Set<UUID> connectingPlayers = new HashSet<>();

    // Portal 1
    private boolean portal1Enabled;
    private String portal1World;
    private int portal1MinX;
    private int portal1MaxX;
    private int portal1MinY;
    private int portal1MaxY;
    private int portal1MinZ;
    private int portal1MaxZ;
    private String portal1TargetServer;

    // Portal 2
    private boolean portal2Enabled;
    private String portal2World;
    private int portal2MinX;
    private int portal2MaxX;
    private int portal2MinY;
    private int portal2MaxY;
    private int portal2MinZ;
    private int portal2MaxZ;
    private String portal2TargetServer;

    private String connectingMessage;

    private String commandPlayerOnlyMessage;
    private String commandUsageMessage;
    private String commandInvalidServerMessage;
    private String commandPlayerNotFoundMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPortalConfig();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("HexPortalConnect wlaczony.");

        getLogger().info("Portal 1: world=" + portal1World
                + " x=" + portal1MinX + ".." + portal1MaxX
                + " y=" + portal1MinY + ".." + portal1MaxY
                + " z=" + portal1MinZ + ".." + portal1MaxZ
                + " -> server=" + portal1TargetServer);

        getLogger().info("Portal 2: world=" + portal2World
                + " x=" + portal2MinX + ".." + portal2MaxX
                + " y=" + portal2MinY + ".." + portal2MaxY
                + " z=" + portal2MinZ + ".." + portal2MaxZ
                + " -> server=" + portal2TargetServer);


    }

    @Override
    public void onDisable() {
        connectingPlayers.clear();
    }

    private void loadPortalConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        connectingMessage = color(config.getString("messages.connecting", "&6Laczenie z serwerem..."));

        // Portal 1
        portal1Enabled = config.getBoolean("portal-1.enabled", true);
        portal1World = config.getString("portal-1.world", "world");

        portal1MinX = config.getInt("portal-1.min-x", 612);
        portal1MaxX = config.getInt("portal-1.max-x", 612);

        portal1MinY = config.getInt("portal-1.min-y", -25);
        portal1MaxY = config.getInt("portal-1.max-y", -17);

        portal1MinZ = config.getInt("portal-1.min-z", 685);
        portal1MaxZ = config.getInt("portal-1.max-z", 695);

        portal1TargetServer = config.getString("portal-1.target-server", "event");

        // Portal 2
        portal2Enabled = config.getBoolean("portal-2.enabled", true);
        portal2World = config.getString("portal-2.world", "world");

        portal2MinX = config.getInt("portal-2.min-x", -6);
        portal2MaxX = config.getInt("portal-2.max-x", -6);

        portal2MinY = config.getInt("portal-2.min-y", 93);
        portal2MaxY = config.getInt("portal-2.max-y", 97);

        portal2MinZ = config.getInt("portal-2.min-z", 36);
        portal2MaxZ = config.getInt("portal-2.max-z", 39);

        portal2TargetServer = config.getString("portal-2.target-server", "superflat");

        commandPlayerOnlyMessage = color(config.getString("messages.console-only", "&cTej komendy nie moze uzyc gracz."));
        commandUsageMessage = color(config.getString("messages.hexconnect-usage", "&eUzycie: /hexconnect <nazwaserwera> <gracz>"));
        commandInvalidServerMessage = color(config.getString("messages.invalid-server", "&cNiepoprawna nazwa serwera."));
        commandPlayerNotFoundMessage = color(config.getString("messages.player-not-found", "&cPodany gracz nie jest online."));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }

        if (sameBlock(event)) {
            return;
        }

        Player player = event.getPlayer();

        if (connectingPlayers.contains(player.getUniqueId())) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();

        // Portal 1
        if (portal1Enabled
                && worldName.equalsIgnoreCase(portal1World)
                && isInsidePortal(x, y, z, portal1MinX, portal1MaxX, portal1MinY, portal1MaxY, portal1MinZ, portal1MaxZ)) {
            connectPlayerToServer(player, portal1TargetServer);
            return;
        }

        // Portal 2
        if (portal2Enabled
                && worldName.equalsIgnoreCase(portal2World)
                && isInsidePortal(x, y, z, portal2MinX, portal2MaxX, portal2MinY, portal2MaxY, portal2MinZ, portal2MaxZ)) {
            connectPlayerToServer(player, portal2TargetServer);
        }
    }

    private boolean sameBlock(PlayerMoveEvent event) {
        return event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ();
    }

    private boolean isInsidePortal(int x, int y, int z,
                                   int minX, int maxX,
                                   int minY, int maxY,
                                   int minZ, int maxZ) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    private void connectPlayerToServer(Player player, String serverName) {
        UUID uuid = player.getUniqueId();
        connectingPlayers.add(uuid);

        player.sendMessage(connectingMessage);
        sendToServer(player, serverName);

        Bukkit.getScheduler().runTaskLater(this, () -> connectingPlayers.remove(uuid), 40L);
    }

    private void sendToServer(Player player, String serverName) {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(byteStream);

        try {
            out.writeUTF("Connect");
            out.writeUTF(serverName);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie przygotowac plugin message dla gracza " + player.getName());
            e.printStackTrace();
            return;
        }

        player.sendPluginMessage(this, "BungeeCord", byteStream.toByteArray());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("hexconnect")) {
            return false;
        }

        if (sender instanceof Player) {
            sender.sendMessage(commandPlayerOnlyMessage);
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(commandUsageMessage);
            return true;
        }

        String serverName = args[0].trim();
        if (!isValidServerName(serverName)) {
            sender.sendMessage(commandInvalidServerMessage);
            return true;
        }

        String playerName = args[1].trim();
        Player targetPlayer = Bukkit.getPlayerExact(playerName);
        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage(commandPlayerNotFoundMessage);
            return true;
        }

        connectPlayerToServer(targetPlayer, serverName);
        return true;
    }

    // Dopuszczamy znaki najczesciej spotykane w nazwach backendow/proxy.
    private boolean isValidServerName(String serverName) {
        return !serverName.isEmpty() && serverName.length() <= 64 && serverName.matches("^[A-Za-z0-9._-]+$");
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
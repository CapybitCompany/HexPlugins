package hex.minigames.persistence;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlayerSnapshotRepository {
    private final Plugin plugin;
    private final File databaseFile;
    private boolean available;

    public PlayerSnapshotRepository(Plugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "snapshots.db");
    }

    public void initialize() {
        plugin.getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS minigames_snapshots (
                          player_uuid TEXT PRIMARY KEY,
                          player_name TEXT NOT NULL,
                          instance_id TEXT,
                          created_at INTEGER NOT NULL,
                          restore_pending INTEGER NOT NULL,
                          inventory TEXT NOT NULL,
                          armor TEXT NOT NULL,
                          extra TEXT NOT NULL,
                          cursor TEXT,
                          held_slot INTEGER NOT NULL,
                          game_mode TEXT NOT NULL,
                          allow_flight INTEGER NOT NULL,
                          flying INTEGER NOT NULL,
                          exp REAL NOT NULL,
                          level INTEGER NOT NULL,
                          total_exp INTEGER NOT NULL,
                          health REAL NOT NULL,
                          absorption REAL NOT NULL,
                          food INTEGER NOT NULL,
                          saturation REAL NOT NULL,
                          exhaustion REAL NOT NULL,
                          effects TEXT NOT NULL,
                          world TEXT NOT NULL,
                          x REAL NOT NULL,
                          y REAL NOT NULL,
                          z REAL NOT NULL,
                          yaw REAL NOT NULL,
                          pitch REAL NOT NULL
                        )
                        """);
            }
            available = true;
        } catch (Throwable error) {
            available = false;
            plugin.getLogger().severe("SQLite minigames snapshot storage is unavailable: " + rootMessage(error));
        }
    }

    public boolean available() {
        return available;
    }

    public synchronized boolean saveIfAbsent(Player player, UUID instanceId) {
        ensureAvailable();
        return saveIfAbsent(player, instanceId, player.getLocation());
    }

    public synchronized boolean saveIfAbsent(Player player, UUID instanceId, Location restoreLocation) {
        ensureAvailable();
        StoredPlayerState state = capture(player, instanceId, restoreLocation == null ? player.getLocation() : restoreLocation);
        String sql = """
                INSERT OR IGNORE INTO minigames_snapshots
                (player_uuid, player_name, instance_id, created_at, restore_pending, inventory, armor, extra, cursor,
                 held_slot, game_mode, allow_flight, flying, exp, level, total_exp, health, absorption, food, saturation,
                 exhaustion, effects, world, x, y, z, yaw, pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, state);
            return statement.executeUpdate() == 1;
        } catch (Exception error) {
            throw new IllegalStateException("Could not save player snapshot", error);
        }
    }

    public synchronized Optional<StoredPlayerState> find(UUID playerId) {
        ensureAvailable();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM minigames_snapshots WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not load player snapshot", error);
        }
    }

    public synchronized List<StoredPlayerState> findByInstance(UUID instanceId) {
        ensureAvailable();
        List<StoredPlayerState> out = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM minigames_snapshots WHERE instance_id = ?")) {
            statement.setString(1, instanceId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) out.add(read(resultSet));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not load instance snapshots", error);
        }
        return out;
    }

    public synchronized List<StoredPlayerState> findAll() {
        ensureAvailable();
        List<StoredPlayerState> out = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM minigames_snapshots")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) out.add(read(resultSet));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not load snapshots", error);
        }
        return out;
    }

    public synchronized void markRestorePending(UUID playerId) {
        ensureAvailable();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("UPDATE minigames_snapshots SET restore_pending = 1 WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Could not mark snapshot restore-pending", error);
        }
    }

    public synchronized void delete(UUID playerId) {
        ensureAvailable();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM minigames_snapshots WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("Could not delete player snapshot", error);
        }
    }

    public static boolean restore(Player player, StoredPlayerState state) {
        var targetWorld = Bukkit.getWorld(state.worldName());
        if (targetWorld == null) return false;
        Location target = new Location(targetWorld, state.x(), state.y(), state.z(), state.yaw(), state.pitch());
        player.teleport(target);
        player.getInventory().clear();
        player.getInventory().setStorageContents(state.inventoryContents());
        player.getInventory().setArmorContents(state.armorContents());
        player.getInventory().setExtraContents(state.extraContents());
        player.setItemOnCursor(state.cursorItem() == null ? new ItemStack(Material.AIR) : state.cursorItem());
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, state.heldSlot())));
        player.setGameMode(state.gameMode() == null ? GameMode.SURVIVAL : state.gameMode());
        player.setAllowFlight(state.allowFlight());
        player.setFlying(state.allowFlight() && state.flying());
        player.setExp(state.exp());
        player.setLevel(state.level());
        player.setTotalExperience(state.totalExperience());
        double maxHealth = player.getMaxHealth();
        player.setHealth(Math.max(0.1, Math.min(maxHealth, state.health())));
        player.setAbsorptionAmount(Math.max(0.0, state.absorption()));
        player.setFoodLevel(Math.max(0, Math.min(20, state.foodLevel())));
        player.setSaturation(Math.max(0.0f, state.saturation()));
        player.setExhaustion(Math.max(0.0f, state.exhaustion()));
        for (PotionEffect effect : List.copyOf(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : state.potionEffects()) {
            player.addPotionEffect(effect);
        }
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
        player.updateInventory();
        return true;
    }

    private StoredPlayerState capture(Player player, UUID instanceId, Location location) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && cursor.getType() == Material.AIR) cursor = null;
        return new StoredPlayerState(
                player.getUniqueId(),
                player.getName(),
                instanceId,
                System.currentTimeMillis(),
                false,
                player.getInventory().getStorageContents().clone(),
                player.getInventory().getArmorContents().clone(),
                player.getInventory().getExtraContents().clone(),
                cursor == null ? null : cursor.clone(),
                player.getInventory().getHeldItemSlot(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getExp(),
                player.getLevel(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getAbsorptionAmount(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                List.copyOf(player.getActivePotionEffects()),
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    private void bind(PreparedStatement statement, StoredPlayerState state) throws Exception {
        statement.setString(1, state.playerId().toString());
        statement.setString(2, state.playerName());
        statement.setString(3, state.instanceId() == null ? null : state.instanceId().toString());
        statement.setLong(4, state.createdAtMillis());
        statement.setInt(5, state.restorePending() ? 1 : 0);
        statement.setString(6, ItemCodec.encode(state.inventoryContents()));
        statement.setString(7, ItemCodec.encode(state.armorContents()));
        statement.setString(8, ItemCodec.encode(state.extraContents()));
        statement.setString(9, state.cursorItem() == null ? "" : ItemCodec.encode(state.cursorItem()));
        statement.setInt(10, state.heldSlot());
        statement.setString(11, state.gameMode().name());
        statement.setInt(12, state.allowFlight() ? 1 : 0);
        statement.setInt(13, state.flying() ? 1 : 0);
        statement.setFloat(14, state.exp());
        statement.setInt(15, state.level());
        statement.setInt(16, state.totalExperience());
        statement.setDouble(17, state.health());
        statement.setDouble(18, state.absorption());
        statement.setInt(19, state.foodLevel());
        statement.setFloat(20, state.saturation());
        statement.setFloat(21, state.exhaustion());
        statement.setString(22, ItemCodec.encode(state.potionEffects()));
        statement.setString(23, state.worldName());
        statement.setDouble(24, state.x());
        statement.setDouble(25, state.y());
        statement.setDouble(26, state.z());
        statement.setFloat(27, state.yaw());
        statement.setFloat(28, state.pitch());
    }

    private StoredPlayerState read(ResultSet rs) throws Exception {
        UUID instanceId = rs.getString("instance_id") == null ? null : UUID.fromString(rs.getString("instance_id"));
        return new StoredPlayerState(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                instanceId,
                rs.getLong("created_at"),
                rs.getInt("restore_pending") == 1,
                ItemCodec.decodeItems(rs.getString("inventory")),
                ItemCodec.decodeItems(rs.getString("armor")),
                ItemCodec.decodeItems(rs.getString("extra")),
                ItemCodec.decodeItem(rs.getString("cursor")),
                rs.getInt("held_slot"),
                GameMode.valueOf(rs.getString("game_mode")),
                rs.getInt("allow_flight") == 1,
                rs.getInt("flying") == 1,
                rs.getFloat("exp"),
                rs.getInt("level"),
                rs.getInt("total_exp"),
                rs.getDouble("health"),
                rs.getDouble("absorption"),
                rs.getInt("food"),
                rs.getFloat("saturation"),
                rs.getFloat("exhaustion"),
                ItemCodec.decodeEffects(rs.getString("effects")),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch")
        );
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private void ensureAvailable() {
        if (!available) throw new IllegalStateException("Snapshot storage is not available");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

package hex.towns.placeholder;

import hex.towns.config.TownsConfig;
import hex.towns.model.ChunkPos;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class TownsPlaceholderExpansion extends PlaceholderExpansion {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final TownsService service;
    private final TownsConfig config;

    public TownsPlaceholderExpansion(TownsService service, TownsConfig config) {
        this.service = service;
        this.config = config;
    }

    @Override public @NotNull String getIdentifier() { return "hextowns"; }
    @Override public @NotNull String getAuthor() { return "HexTeam"; }
    @Override public @NotNull String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        String key = params.toLowerCase(Locale.ROOT);
        Optional<UUID> townId = service.townIdOf(player.getUniqueId());
        Optional<Town> town = townId.flatMap(service::findTown);

        return switch (key) {
            case "has_town" -> bool(town.isPresent());
            case "is_owner" -> bool(townId.filter(id -> service.isOwner(player.getUniqueId(), id)).isPresent());
            case "is_coop" -> bool(townId.filter(id -> !service.isOwner(player.getUniqueId(), id)).isPresent());
            case "role" -> role(player, townId);
            case "role_display" -> roleDisplay(player, townId);
            case "town_uuid", "uuid" -> town.map(Town::id).map(UUID::toString).orElse("");
            case "town_name", "name" -> town.map(Town::name).orElse("Brak miasta");
            case "owner_uuid" -> town.map(Town::ownerId).map(UUID::toString).orElse("");
            case "owner_name" -> town.map(value -> playerName(value.ownerId())).orElse("-");
            case "members" -> town.map(value -> String.valueOf(service.membersOf(value).size())).orElse("0");
            case "max_members" -> String.valueOf(config.maxMembers());
            case "chunks" -> town.map(value -> String.valueOf(service.chunksOf(value).size())).orElse("0");
            case "max_chunks" -> String.valueOf(config.maxChunks());
            case "growth", "growth_points" -> town.map(value -> String.valueOf(value.growthPoints())).orElse("0");
            case "world" -> town.map(Town::world).orElse(player.getWorld().getName());
            case "heart" -> town.map(value -> formatChunk(value.heart())).orElse("-");
            case "heart_x" -> town.map(value -> String.valueOf(value.heart().x())).orElse("0");
            case "heart_z" -> town.map(value -> String.valueOf(value.heart().z())).orElse("0");
            case "created_at" -> town.map(value -> DATE_FORMAT.format(value.createdAt())).orElse("-");
            case "current_chunk" -> currentChunk(player);
            case "current_chunk_x" -> String.valueOf(player.getChunk().getX());
            case "current_chunk_z" -> String.valueOf(player.getChunk().getZ());
            case "here_has_town" -> bool(service.townAt(player.getLocation()).isPresent());
            case "here_town_name" -> service.townAt(player.getLocation()).map(Town::name).orElse("Dzicz");
            case "here_is_own" -> bool(service.townAt(player.getLocation()).map(value -> service.isMember(player.getUniqueId(), value.id())).orElse(false));
            case "can_build_here" -> bool(service.canBuild(player, player.getLocation()));
            case "claim_cost_growth" -> "1";
            case "create_initial_chunks" -> String.valueOf(initialChunks());
            case "create_min_distance" -> String.valueOf(config.minDistanceChunks());
            case "buffer_chunks" -> String.valueOf(config.bufferChunks());
            case "confirm_seconds" -> String.valueOf(config.confirmWindowSeconds());
            case "visual_radius" -> String.valueOf(config.visualRadiusChunks());
            default -> "";
        };
    }

    private String role(Player player, Optional<UUID> townId) {
        if (townId.isEmpty()) return "NONE";
        return service.isOwner(player.getUniqueId(), townId.get()) ? "OWNER" : "COOP";
    }

    private String roleDisplay(Player player, Optional<UUID> townId) {
        return switch (role(player, townId)) {
            case "OWNER" -> "Właściciel";
            case "COOP" -> "COOP";
            default -> "Brak miasta";
        };
    }

    private String playerName(UUID uuid) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        return offlinePlayer.getName() == null ? uuid.toString() : offlinePlayer.getName();
    }

    private String currentChunk(Player player) {
        Chunk chunk = player.getChunk();
        return chunk.getX() + ", " + chunk.getZ();
    }

    private String formatChunk(ChunkPos chunk) {
        return chunk.x() + ", " + chunk.z();
    }

    private int initialChunks() {
        int diameter = config.initialRadius() * 2 + 1;
        return diameter * diameter;
    }

    private static String bool(boolean value) {
        return String.valueOf(value);
    }
}


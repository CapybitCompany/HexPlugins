package hex.towns.placeholder;

import hex.towns.config.TownsConfig;
import hex.towns.model.ChunkPos;
import hex.towns.model.Town;
import hex.towns.model.TownRole;
import hex.towns.service.TownsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
            case "name_max_length", "max_name_length" -> String.valueOf(config.maxNameLength());
            case "default_name_template" -> config.defaultNameTemplate();
            default -> dynamicTownPlaceholder(player, town.orElse(null), key);
        };
    }


    private String dynamicTownPlaceholder(Player viewer, Town town, String key) {
        if (town == null) return "";
        if (key.startsWith("member_")) return memberPlaceholder(town, key);
        if (key.startsWith("request_")) return requestPlaceholder(town, key);
        return "";
    }

    private String memberPlaceholder(Town town, String key) {
        IndexedField parsed = IndexedField.parse(key, "member_");
        if (parsed == null || parsed.index() <= 0) return "";
        List<TownsService.MemberInfo> members = service.memberInfos(town);
        if (parsed.field().equals("count")) return String.valueOf(members.size());
        if (parsed.index() > members.size()) return missingMemberDefault(parsed.field());
        TownsService.MemberInfo member = members.get(parsed.index() - 1);
        return switch (parsed.field()) {
            case "exists" -> "true";
            case "uuid", "id" -> member.playerId().toString();
            case "name", "nick", "skull_owner" -> member.name();
            case "rank", "role" -> member.role().name();
            case "rank_display", "role_display" -> member.role() == TownRole.OWNER ? "Właściciel" : "COOP";
            case "online" -> bool(member.online());
            case "online_display", "status" -> member.online() ? "Online" : "Offline";
            case "material" -> "PLAYER_HEAD";
            default -> "";
        };
    }

    private String requestPlaceholder(Town town, String key) {
        IndexedField parsed = IndexedField.parse(key, "request_");
        if (parsed == null || parsed.index() <= 0) return "";
        List<TownsService.CoopRequestInfo> requests = service.pendingCoopRequests(town, 7);
        if (parsed.field().equals("count")) return String.valueOf(requests.size());
        if (parsed.index() > requests.size()) return missingRequestDefault(parsed.field());
        TownsService.CoopRequestInfo request = requests.get(parsed.index() - 1);
        return switch (parsed.field()) {
            case "exists" -> "true";
            case "uuid", "id" -> request.playerId().toString();
            case "name", "nick", "skull_owner" -> request.name();
            case "material" -> "PLAYER_HEAD";
            default -> "";
        };
    }

    private String missingMemberDefault(String field) {
        return switch (field) {
            case "exists", "online" -> "false";
            case "material" -> "LIGHT_GRAY_STAINED_GLASS_PANE";
            case "name", "nick", "skull_owner" -> "";
            case "rank", "role", "rank_display", "role_display", "online_display", "status" -> "-";
            default -> "";
        };
    }

    private String missingRequestDefault(String field) {
        return switch (field) {
            case "exists" -> "false";
            case "material" -> "WHITE_STAINED_GLASS_PANE";
            case "name", "nick", "skull_owner" -> "";
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

    private record IndexedField(int index, String field) {
        static IndexedField parse(String key, String prefix) {
            if (!key.startsWith(prefix)) return null;
            String raw = key.substring(prefix.length());
            int split = raw.indexOf('_');
            if (split <= 0 || split >= raw.length() - 1) return null;
            try {
                return new IndexedField(Integer.parseInt(raw.substring(0, split)), raw.substring(split + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}


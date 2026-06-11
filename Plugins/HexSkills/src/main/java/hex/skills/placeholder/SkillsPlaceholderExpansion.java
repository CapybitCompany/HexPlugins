package hex.skills.placeholder;

import hex.skills.config.SkillRegistry;
import hex.skills.database.SkillRepository;
import hex.skills.model.SkillDefinition;
import hex.towns.api.TownsApi;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class SkillsPlaceholderExpansion extends PlaceholderExpansion {
    private final TownsApi towns;
    private final SkillRepository repository;
    private final Supplier<SkillRegistry> registrySupplier;

    public SkillsPlaceholderExpansion(TownsApi towns, SkillRepository repository, Supplier<SkillRegistry> registrySupplier) {
        this.towns = towns;
        this.repository = repository;
        this.registrySupplier = registrySupplier;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexskills";
    }

    @Override
    public @NotNull String getAuthor() {
        return "HexTeam";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
        if (offlinePlayer == null) return "";
        String normalized = params.toLowerCase(Locale.ROOT);
        if (normalized.equals("count")) return String.valueOf(registrySupplier.get().all().size());
        if (normalized.equals("list")) {
            return registrySupplier.get().all().stream()
                    .map(SkillDefinition::displayName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("-");
        }

        String[] parts = normalized.split("_");
        if (parts.length < 2) return "";
        String skillId = parts[0];
        String field = normalized.substring(skillId.length() + 1);
        Optional<SkillDefinition> skill = registrySupplier.get().byId(skillId);
        if (skill.isEmpty()) return "";

        UUID playerUuid = offlinePlayer.getUniqueId();
        Optional<UUID> townId = towns.townIdOf(playerUuid);
        if (townId.isEmpty()) return "0";

        SkillDefinition definition = skill.get();
        SkillRepository.Progress progress = repository.getProgress(townId.get(), playerUuid, definition.id()).orElse(new SkillRepository.Progress(0L, 0));
        long xp = progress.xp();
        int level = Math.min(definition.maxLevel(), progress.level());
        long currentLevelXp = Math.min(xp, (long) level * definition.xpPerLevel());
        long nextLevelXp = level >= definition.maxLevel() ? (long) definition.maxLevel() * definition.xpPerLevel() : (long) (level + 1) * definition.xpPerLevel();
        long xpInLevel = level >= definition.maxLevel() ? definition.xpPerLevel() : Math.max(0L, xp - currentLevelXp);
        long xpToNext = level >= definition.maxLevel() ? 0L : Math.max(0L, nextLevelXp - xp);
        int percent = level >= definition.maxLevel() ? 100 : (int) Math.max(0, Math.min(100, Math.round(xpInLevel * 100.0D / definition.xpPerLevel())));

        return switch (field) {
            case "id" -> definition.id();
            case "display", "display_name", "name" -> definition.displayName();
            case "level" -> String.valueOf(level);
            case "max_level" -> String.valueOf(definition.maxLevel());
            case "xp" -> String.valueOf(xp);
            case "xp_per_level" -> String.valueOf(definition.xpPerLevel());
            case "xp_in_level" -> String.valueOf(xpInLevel);
            case "next_xp" -> String.valueOf(nextLevelXp);
            case "xp_to_next" -> String.valueOf(xpToNext);
            case "progress_percent", "percent" -> String.valueOf(percent);
            case "progress_bar", "bar" -> progressBar(percent, 20);
            case "status" -> level >= definition.maxLevel() ? "MAX" : xpInLevel + "/" + definition.xpPerLevel();
            default -> "";
        };
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        return onRequest(player, params);
    }

    private String progressBar(int percent, int bars) {
        int filled = (int) Math.round((percent / 100.0D) * bars);
        return "|".repeat(Math.max(0, filled)) + ".".repeat(Math.max(0, bars - filled));
    }
}

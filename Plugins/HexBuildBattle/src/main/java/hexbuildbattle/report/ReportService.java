package hexbuildbattle.report;

import hexbuildbattle.arena.CuboidRegion;
import hexbuildbattle.score.RoundBuildScore;
import hexbuildbattle.theme.Theme;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class ReportService {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter ID_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter REPORT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaPlugin plugin;
    private final Map<UUID, ActiveReport> activeReports = new LinkedHashMap<>();

    public ReportService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void clearRoundReports() {
        activeReports.clear();
    }

    public ReportResult report(Player reporter, RoundBuildScore score, Theme theme) {
        ActiveReport existing = activeReports.get(score.ownerId());
        if (existing != null) {
            return addReporter(existing, reporter);
        }

        try {
            ActiveReport report = createReport(reporter, score, theme);
            activeReports.put(score.ownerId(), report);
            new WorldEditSchematicBridge().save(score.arena().buildRegion(), report.schematicFile);
            saveReportFile(report);
            return ReportResult.created(report.id, report.metadataFile, report.schematicFile);
        } catch (Exception ex) {
            activeReports.remove(score.ownerId());
            deletePartialSchematic(score, ex);
            plugin.getLogger().log(Level.WARNING, "Failed to save build report for " + score.ownerName() + ".", ex);
            return ReportResult.failed();
        }
    }

    private ReportResult addReporter(ActiveReport report, Player reporter) {
        UUID reporterId = reporter.getUniqueId();
        if (report.reporters.containsKey(reporterId)) {
            return ReportResult.duplicate(report.id, report.metadataFile, report.schematicFile);
        }

        LocalDateTime now = LocalDateTime.now();
        ReporterSnapshot snapshot = new ReporterSnapshot(
                reporterId,
                reporter.getName(),
                REPORT_TIME_FORMAT.format(now)
        );
        report.reporters.put(reporterId, snapshot);
        report.updatedAt = REPORT_TIME_FORMAT.format(now);
        try {
            saveReportFile(report);
            return ReportResult.updated(report.id, report.metadataFile, report.schematicFile);
        } catch (IOException ex) {
            report.reporters.remove(reporterId);
            plugin.getLogger().log(Level.WARNING, "Failed to update build report " + report.id + ".", ex);
            return ReportResult.failed();
        }
    }

    private ActiveReport createReport(Player reporter, RoundBuildScore score, Theme theme) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        File folder = reportFolder(now);
        String id = uniqueReportId(folder, now, score);
        File metadataFile = new File(folder, id + ".yml");
        File schematicFile = new File(folder, id + ".schem");
        CuboidRegion region = score.arena().buildRegion();
        ActiveReport report = new ActiveReport(
                id,
                REPORT_TIME_FORMAT.format(now),
                REPORT_TIME_FORMAT.format(now),
                score.ownerId(),
                score.ownerName(),
                score.arena().index(),
                score.arena().displayName(),
                theme == null ? "" : theme.id(),
                theme == null ? "" : theme.displayName(),
                region,
                metadataFile,
                schematicFile
        );
        report.reporters.put(reporter.getUniqueId(), new ReporterSnapshot(
                reporter.getUniqueId(),
                reporter.getName(),
                REPORT_TIME_FORMAT.format(now)
        ));
        return report;
    }

    private File reportFolder(LocalDateTime now) throws IOException {
        File folder = new File(plugin.getDataFolder(), "reports" + File.separator + DAY_FORMAT.format(now));
        Files.createDirectories(folder.toPath());
        return folder;
    }

    private String uniqueReportId(File folder, LocalDateTime now, RoundBuildScore score) {
        String base = "report-" + ID_TIME_FORMAT.format(now)
                + "-arena-" + score.arena().index()
                + "-" + score.ownerId().toString().substring(0, 8);
        String candidate = base;
        int suffix = 2;
        while (new File(folder, candidate + ".yml").exists()
                || new File(folder, candidate + ".schem").exists()) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void deletePartialSchematic(RoundBuildScore score, Exception ex) {
        File today = new File(plugin.getDataFolder(), "reports" + File.separator + DAY_FORMAT.format(LocalDateTime.now()));
        File[] files = today.listFiles((folder, name) ->
                name.endsWith("-" + score.ownerId().toString().substring(0, 8) + ".schem")
                        && new File(folder, name).length() == 0L);
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.delete()) {
                plugin.getLogger().log(Level.FINE, "Could not delete partial schematic " + file + " after " + ex.getClass().getSimpleName());
            }
        }
    }

    private void saveReportFile(ActiveReport report) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("id", report.id);
        yaml.set("status", "open");
        yaml.set("created-at", report.createdAt);
        yaml.set("updated-at", report.updatedAt);
        yaml.set("reported-player.name", report.ownerName);
        yaml.set("reported-player.uuid", report.ownerId.toString());
        yaml.set("arena.index", report.arenaIndex);
        yaml.set("arena.name", report.arenaName);
        yaml.set("theme.id", report.themeId);
        yaml.set("theme.name", report.themeName);
        yaml.set("schematic", relativePath(report.schematicFile));
        yaml.set("region.world", report.region.world().getName());
        yaml.set("region.min.x", report.region.minX());
        yaml.set("region.min.y", report.region.minY());
        yaml.set("region.min.z", report.region.minZ());
        yaml.set("region.max.x", report.region.maxX());
        yaml.set("region.max.y", report.region.maxY());
        yaml.set("region.max.z", report.region.maxZ());
        yaml.set("reporters", reporterRows(report));
        yaml.save(report.metadataFile);
    }

    private List<Map<String, String>> reporterRows(ActiveReport report) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (ReporterSnapshot reporter : report.reporters.values()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", reporter.name);
            row.put("uuid", reporter.uuid.toString());
            row.put("reported-at", reporter.reportedAt);
            rows.add(row);
        }
        return rows;
    }

    private String relativePath(File file) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path target = file.toPath().toAbsolutePath().normalize();
        try {
            return dataFolder.relativize(target).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException ignored) {
            return target.toString();
        }
    }

    public enum ReportStatus {
        CREATED,
        UPDATED,
        DUPLICATE,
        FAILED
    }

    public record ReportResult(ReportStatus status, String id, File metadataFile, File schematicFile) {

        static ReportResult created(String id, File metadataFile, File schematicFile) {
            return new ReportResult(ReportStatus.CREATED, id, metadataFile, schematicFile);
        }

        static ReportResult updated(String id, File metadataFile, File schematicFile) {
            return new ReportResult(ReportStatus.UPDATED, id, metadataFile, schematicFile);
        }

        static ReportResult duplicate(String id, File metadataFile, File schematicFile) {
            return new ReportResult(ReportStatus.DUPLICATE, id, metadataFile, schematicFile);
        }

        static ReportResult failed() {
            return new ReportResult(ReportStatus.FAILED, "", null, null);
        }
    }

    private static final class ActiveReport {

        private final String id;
        private final String createdAt;
        private String updatedAt;
        private final UUID ownerId;
        private final String ownerName;
        private final int arenaIndex;
        private final String arenaName;
        private final String themeId;
        private final String themeName;
        private final CuboidRegion region;
        private final File metadataFile;
        private final File schematicFile;
        private final Map<UUID, ReporterSnapshot> reporters = new LinkedHashMap<>();

        private ActiveReport(
                String id,
                String createdAt,
                String updatedAt,
                UUID ownerId,
                String ownerName,
                int arenaIndex,
                String arenaName,
                String themeId,
                String themeName,
                CuboidRegion region,
                File metadataFile,
                File schematicFile
        ) {
            this.id = id;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.arenaIndex = arenaIndex;
            this.arenaName = arenaName;
            this.themeId = themeId;
            this.themeName = themeName;
            this.region = region;
            this.metadataFile = metadataFile;
            this.schematicFile = schematicFile;
        }
    }

    private record ReporterSnapshot(UUID uuid, String name, String reportedAt) {
    }
}

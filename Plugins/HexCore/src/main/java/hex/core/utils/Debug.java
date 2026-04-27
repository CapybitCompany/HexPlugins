package hex.core.utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Debug {
    private static JavaPlugin plugin;
    private static Logger logger;

    // config cache (żeby nie czytać configu 1000x)
    private static boolean debugEnabled;
    private static boolean chatEnabled;
    private static String chatAudience;   // "ops" / "permission"
    private static String chatPermission;
    private static boolean fileEnabled;
    private static long maxFileBytes;

    private static File logDir;
    private static File logFile;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private Debug() {}

    /** Wywołaj raz w onEnable() */
    public static void init(JavaPlugin pl) {
        plugin = Objects.requireNonNull(pl, "plugin");
        logger = plugin.getLogger();

        // load config defaults
        plugin.saveDefaultConfig();
        reload();

        // log foldery
        logDir = new File(plugin.getDataFolder(), "logs");
        if (!logDir.exists()) logDir.mkdirs();

        logFile = new File(logDir, "minecrafthex.log");

        info("Debug.init() -> enabled=" + debugEnabled + ", chat=" + chatEnabled + ", file=" + fileEnabled);
    }

    /** Jeśli robisz /hex reload */
    public static void reload() {
        if (plugin == null) return;

        plugin.reloadConfig();
        debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);

        chatEnabled = plugin.getConfig().getBoolean("debug.chat.enabled", true);
        chatAudience = plugin.getConfig().getString("debug.chat.audience", "ops");
        chatPermission = plugin.getConfig().getString("debug.chat.permission", "minecrafthex.debug");

        fileEnabled = plugin.getConfig().getBoolean("debug.file.enabled", true);
        long maxMb = plugin.getConfig().getLong("debug.file.max_size_mb", 10L);
        maxFileBytes = Math.max(1L, maxMb) * 1024L * 1024L;
    }

    // ----------------------------
    // Public API (chat)
    // ----------------------------

    /** Debug message do devów/OP (wg configu). */
    public static void dbg(String message) {
        if (!debugEnabled) return;
        String line = formatChat("DBG", message);

        if (chatEnabled) {
            broadcastToAudience(line);
        }
        if (fileEnabled) {
            appendToFile("DBG", message, null);
        }
        // dodatkowo do konsoli
        logger.info(stripColor(line));
    }

    /** Debug do konkretnego sendera (np. kto wywołał komendę). */
    public static void dbg(CommandSender to, String message) {
        if (!debugEnabled) return;
        String line = formatChat("DBG", message);
        to.sendMessage(line);

        if (fileEnabled) {
            appendToFile("DBG", message, senderName(to));
        }
    }

    /** Wiadomość informacyjna (nie debug) – zawsze do konsoli, opcjonalnie do pliku. */
    public static void info(String message) {
        logger.info(prefixPlain("INF", message));
        if (fileEnabled) appendToFile("INF", message, null);
    }

    public static void warn(String message) {
        logger.warning(prefixPlain("WRN", message));
        if (fileEnabled) appendToFile("WRN", message, null);
    }

    public static void error(String message, Throwable t) {
        logger.log(Level.SEVERE, prefixPlain("ERR", message), t);
        if (fileEnabled) appendToFile("ERR", message + " | ex=" + t.getClass().getSimpleName() + ": " + t.getMessage(), null);
    }

    // ----------------------------
    // Internal: audience + format
    // ----------------------------

    private static void broadcastToAudience(String line) {
        // console też dostanie
        Bukkit.getConsoleSender().sendMessage(stripColor(line));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if ("permission".equalsIgnoreCase(chatAudience)) {
                if (p.hasPermission(chatPermission)) p.sendMessage(line);
            } else { // ops
                if (p.isOp()) p.sendMessage(line);
            }
        }
    }

    private static String formatChat(String level, String message) {
        // prosto i czytelnie, bez Adventure
        return ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "MinecraftHex" + ChatColor.DARK_GRAY + "] "
                + ChatColor.GRAY + "[" + colorLevel(level) + level + ChatColor.GRAY + "] "
                + ChatColor.WHITE + message;
    }

    private static ChatColor colorLevel(String level) {
        return switch (level) {
            case "DBG" -> ChatColor.LIGHT_PURPLE;
            case "INF" -> ChatColor.GREEN;
            case "WRN" -> ChatColor.GOLD;
            case "ERR" -> ChatColor.RED;
            default -> ChatColor.GRAY;
        };
    }

    private static String prefixPlain(String level, String message) {
        return "[MinecraftHex] [" + level + "] " + message;
    }

    private static String stripColor(String text) {
        return ChatColor.stripColor(text);
    }

    private static String senderName(CommandSender sender) {
        if (sender == null) return null;
        if (sender instanceof Player p) return p.getName();
        return sender.getName();
    }

    // ----------------------------
    // File logging (+ prosta rotacja)
    // ----------------------------

    private static void appendToFile(String level, String message, String actor) {
        if (plugin == null || logFile == null) return;

        rotateIfTooBig();

        String ts = TS.format(Instant.now());
        String who = (actor == null || actor.isBlank()) ? "-" : actor;
        String line = ts + " [" + level + "] [" + who + "] " + message;

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            // fallback: console
            logger.warning("Nie mogę zapisać do logu: " + e.getMessage());
        }
    }

    private static void rotateIfTooBig() {
        if (!logFile.exists()) return;
        if (logFile.length() < maxFileBytes) return;

        File rotated = new File(logDir, "minecrafthex-" + System.currentTimeMillis() + ".log");
        boolean ok = logFile.renameTo(rotated);
        if (!ok) {
            // jak rename nie działa (np. lock), to olewamy rotację
            logger.warning("Nie udało się zrotować logu (renameTo=false).");
        }
    }
}


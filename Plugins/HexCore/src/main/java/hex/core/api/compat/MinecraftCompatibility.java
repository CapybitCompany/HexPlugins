package hex.core.api.compat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Central runtime compatibility check for the Hex plugin set.
 *
 * The Hex plugins intentionally stay on public Bukkit/Paper APIs. Purpur is
 * treated as the target runtime because it is a Paper-compatible server, but no
 * plugin should depend on org.purpurmc or NMS/CraftBukkit internals just to boot.
 */
public final class MinecraftCompatibility {
    public static final String TARGET_MINECRAFT_VERSION = "1.21.11";
    private static final int MAX_VERSION_LOG_LENGTH = 180;

    private MinecraftCompatibility() {
    }

    public static void logStartupCompatibility(JavaPlugin plugin) {
        RuntimeInfo info = runtimeInfo();

        if (TARGET_MINECRAFT_VERSION.equals(info.minecraftVersion())) {
            plugin.getLogger().info("Runtime target OK: Minecraft " + info.minecraftVersion()
                    + " / " + shortServerVersion(info.serverVersion()));
        } else if (isNewerThanTarget(info.minecraftVersion())) {
            plugin.getLogger().warning("Serwer raportuje Minecraft " + info.minecraftVersion()
                    + ", a plugin był sprawdzany pod " + TARGET_MINECRAFT_VERSION
                    + ". Start nie jest blokowany, ale po aktualizacji warto zrobić test regresji GUI, Display Entities, receptur i eventów.");
        } else {
            plugin.getLogger().warning("Ten plugin jest dostosowany pod Minecraft/Purpur " + TARGET_MINECRAFT_VERSION
                    + ", a serwer raportuje Minecraft " + info.minecraftVersion() + " / "
                    + shortServerVersion(info.serverVersion()) + ". Serwer starszy od api-version plugin.yml może odmówić załadowania pluginu.");
        }

        if (info.purpurLike()) {
            plugin.getLogger().info("Runtime target OK: wykryto Purpur-compatible server (" + info.serverName() + ").");
        } else if (info.paperLike()) {
            plugin.getLogger().warning("Wykryto Paper/Paper-compatible server, ale nie wykryto Purpur w nazwie/wersji. "
                    + "Plugin używa publicznego Bukkit/Paper API, więc powinien działać, lecz docelowym środowiskiem pozostaje Purpur "
                    + TARGET_MINECRAFT_VERSION + ".");
        } else {
            plugin.getLogger().warning("Nie wykryto Paper/Purpur w nazwie/wersji serwera. Pluginy Hex wymagają środowiska zgodnego z Paper/Purpur "
                    + TARGET_MINECRAFT_VERSION + ".");
        }
    }

    public static boolean isTargetMinecraftVersion() {
        return TARGET_MINECRAFT_VERSION.equals(safeMinecraftVersion());
    }

    public static boolean isPurpurRuntime() {
        return runtimeInfo().purpurLike();
    }

    public static RuntimeInfo runtimeInfo() {
        String minecraftVersion = safeMinecraftVersion();
        String serverVersion = safeString(Bukkit.getVersion(), "unknown server");
        String serverName;
        try {
            serverName = safeString(Bukkit.getName(), "unknown");
        } catch (Throwable ignored) {
            serverName = "unknown";
        }
        String probe = (serverName + " " + serverVersion).toLowerCase(Locale.ROOT);
        boolean purpur = probe.contains("purpur");
        boolean paper = purpur || probe.contains("paper") || probe.contains("folia");
        return new RuntimeInfo(minecraftVersion, serverName, serverVersion, paper, purpur);
    }

    public static String safeMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            return safeString(version, "unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isNewerThanTarget(String actual) {
        int[] actualParts = parseVersion(actual);
        int[] targetParts = parseVersion(TARGET_MINECRAFT_VERSION);
        if (actualParts == null || targetParts == null) {
            return false;
        }
        for (int i = 0; i < Math.max(actualParts.length, targetParts.length); i++) {
            int a = i < actualParts.length ? actualParts[i] : 0;
            int t = i < targetParts.length ? targetParts[i] : 0;
            if (a != t) {
                return a > t;
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String clean = version.trim().split("[-+ ]", 2)[0];
        String[] parts = clean.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        int[] parsed = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                parsed[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return parsed;
    }

    private static String safeString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String shortServerVersion(String serverVersion) {
        if (serverVersion == null || serverVersion.isBlank()) {
            return "unknown server";
        }
        return serverVersion.length() > MAX_VERSION_LOG_LENGTH
                ? serverVersion.substring(0, MAX_VERSION_LOG_LENGTH - 3) + "..."
                : serverVersion;
    }

    public record RuntimeInfo(String minecraftVersion, String serverName, String serverVersion,
                              boolean paperLike, boolean purpurLike) {
    }
}

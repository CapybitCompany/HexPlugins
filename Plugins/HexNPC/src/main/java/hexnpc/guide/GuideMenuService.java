package hexnpc.guide;

import hexnpc.HexNpcPlugin;
import hexnpc.api.HexNpcGuideApi;
import hexnpc.guide.model.GuideMenu;
import hexnpc.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public final class GuideMenuService implements HexNpcGuideApi {
    public static final String ROOT_GUIDE = "server";

    private final HexNpcPlugin plugin;
    private final Logger logger;
    private final GuideMenuRegistry registry;
    private final GuideMenuRenderer renderer = new GuideMenuRenderer();

    public GuideMenuService(HexNpcPlugin plugin, File file, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.registry = new GuideMenuRegistry(file, logger);
    }

    public int reload() {
        int count = registry.reload();
        logger.info("HexNPC: loaded " + count + " guide menu(s).");
        registry.find("server").ifPresent(menu -> logger.info("HexNPC: guide root 'server' entries=" + menu.entries().size()));
        registry.find("arcade").ifPresent(menu -> logger.info("HexNPC: guide root 'arcade' entries=" + menu.entries().size()));
        for (String error : registry.validationErrors()) logger.warning("HexNPC: guide validation: " + error);
        return count;
    }

    @Override
    public boolean openGuide(Player player) {
        return openMenu(player, ROOT_GUIDE);
    }

    public boolean open(Player player, String menuId) {
        return openMenu(player, menuId);
    }

    @Override
    public boolean openMenu(Player player, String menuId) {
        if (player == null || !player.isOnline() || menuId == null || menuId.isBlank()) return false;
        GuideMenu menu = registry.find(menuId).orElse(null);
        if (menu == null) {
            if (Bukkit.isPrimaryThread()) player.sendMessage(LegacyFormat.component("&cTen poradnik jest obecnie niedostępny."));
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            if (!plugin.isEnabled()) return false;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) openMenu(player, menu.id());
            });
            return true;
        }
        try {
            player.openInventory(renderer.render(menu));
            return true;
        } catch (RuntimeException | LinkageError ex) {
            logger.severe("HexNPC: failed to open guide menu '" + menu.id() + "' for " + player.getName());
            ex.printStackTrace();
            player.sendMessage(LegacyFormat.component("&cNie udało się otworzyć poradnika. Zgłoś problem administracji."));
            return false;
        }
    }

    @Override
    public boolean exists(String menuId) {
        return registry.exists(menuId);
    }

    public GuideMenuRegistry registry() { return registry; }
    public List<String> ids() { return registry.ids(); }
    public List<String> validationErrors() { return registry.validationErrors(); }
}

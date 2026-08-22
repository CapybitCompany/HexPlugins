package hexnpc.menu;

import hexnpc.guide.GuideMenuService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;

/** Backward-compatible /hexnpcinfo command formerly provided by HexNPCClickFix. */
public final class LegacyHexNpcInfoCommand implements CommandExecutor {

    private final ClickableMenuService menus;
    private final GuideMenuService guides;

    public LegacyHexNpcInfoCommand(ClickableMenuService menus, GuideMenuService guides) {
        this.menus = Objects.requireNonNull(menus, "menus");
        this.guides = Objects.requireNonNull(guides, "guides");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest przeznaczona dla graczy.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§cPodaj identyfikator menu NPC.");
            return true;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (id.equals("tutorial_on-click_0") && guides.exists("server") && guides.open(player, "server")) {
            return true;
        }
        if (id.equals("kasyno_info_on-click_0") && guides.exists("arcade") && guides.open(player, "arcade")) {
            return true;
        }
        menus.open(player, args[0]);
        return true;
    }
}

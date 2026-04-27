package uiexample;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.time.Duration;

public final class UiExampleCommand implements CommandExecutor {

    private final HexApi api;

    public UiExampleCommand(HexApi api) {
        this.api = api;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage("§cPodaj template key.");
            return true;
        }

        String template = args[0];

        switch (cmd.getName().toLowerCase()) {

            // 1️⃣ Chat do wszystkich
            case "uichatall" -> {
                api.ui().broadcast(template);
                return true;
            }

            // 2️⃣ Chat do 1 gracza
            case "uichat" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUżycie: /uichat <nick> <template>");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("§cNie znaleziono gracza.");
                    return true;
                }

                api.ui().send(target, args[1]);
                return true;
            }

            // 3️⃣ Actionbar do wszystkich
            case "uiactionall" -> {
                var component = api.ui().render(template);

                Bukkit.getOnlinePlayers().forEach(p ->
                        p.sendActionBar(component)
                );

                return true;
            }

            // 4️⃣ Actionbar do 1 gracza
            case "uiaction" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUżycie: /uiaction <nick> <template>");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("§cNie znaleziono gracza.");
                    return true;
                }

                target.sendActionBar(api.ui().render(args[1]));
                return true;
            }

            // 5️⃣ Subtitle do wszystkich
            case "uisubtitleall" -> {
                var subtitle = api.ui().render(template);

                Bukkit.getOnlinePlayers().forEach(p -> {
                    Title title = Title.title(
                            net.kyori.adventure.text.Component.empty(),
                            subtitle,
                            Title.Times.times(
                                    Duration.ZERO,
                                    Duration.ofSeconds(1),
                                    Duration.ofMillis(200)
                            )
                    );
                    p.showTitle(title);
                });

                return true;
            }

            // 6️⃣ Subtitle do 1 gracza
            case "uisubtitle" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUżycie: /uisubtitle <nick> <template>");
                    return true;
                }

                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    sender.sendMessage("§cNie znaleziono gracza.");
                    return true;
                }

                var subtitle = api.ui().render(args[1]);

                Title title = Title.title(
                        net.kyori.adventure.text.Component.empty(),
                        subtitle,
                        Title.Times.times(
                                Duration.ZERO,
                                Duration.ofSeconds(1),
                                Duration.ofMillis(200)
                        )
                );

                target.showTitle(title);
                return true;
            }
        }

        return false;
    }
}

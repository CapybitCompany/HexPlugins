package hexbuildbattle.config;

import hexbuildbattle.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MessageService {

    private final ConfigService configService;

    public MessageService(ConfigService configService) {
        this.configService = configService;
    }

    public String raw(String path) {
        return raw(path, "");
    }

    public String raw(String path, String fallback) {
        String value = messages().getString(path);
        return value == null ? fallback : value;
    }

    public String formatRaw(String path, Map<String, String> placeholders) {
        return Text.replace(raw(path), placeholders);
    }

    public Component component(String path) {
        return Text.component(raw(path));
    }

    public Component component(String path, Map<String, String> placeholders) {
        return Text.component(formatRaw(path, placeholders));
    }

    public void send(CommandSender sender, String path) {
        sender.sendMessage(component(path));
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        sender.sendMessage(component(path, placeholders));
    }

    public void sendWithPrefix(CommandSender sender, String path, Map<String, String> placeholders) {
        String prefix = raw("prefix");
        sender.sendMessage(Text.component(prefix + formatRaw(path, placeholders)));
    }

    public void sendActionBar(Player player, String path, Map<String, String> placeholders) {
        player.sendActionBar(component(path, placeholders));
    }

    public List<String> rawList(String path) {
        List<String> values = messages().getStringList(path);
        return values == null ? Collections.emptyList() : values;
    }

    public void sendList(CommandSender sender, String path, Map<String, String> placeholders) {
        for (String line : rawList(path)) {
            sender.sendMessage(Text.component(Text.replace(line, placeholders)));
        }
    }

    private FileConfiguration messages() {
        return configService.messages();
    }
}

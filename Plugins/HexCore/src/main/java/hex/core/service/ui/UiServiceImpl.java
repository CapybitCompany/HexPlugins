package hex.core.service.ui;

import hex.core.api.config.ConfigKey;
import hex.core.api.config.ConfigService;
import hex.core.api.ui.UiService;
import hex.core.api.ui.UiTokens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UiServiceImpl implements UiService {

    public static final ConfigKey<UiConfig> UI_KEY = new ConfigKey<>("ui", UiConfig.class);

    private final ConfigService configs;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public UiServiceImpl(ConfigService configs) {
        this.configs = configs;
    }

    @Override
    public Component render(String templateKey) {
        return render(templateKey, new UiTokens());
    }

    @Override
    public Component render(String templateKey, UiTokens tokens) {
        UiConfig cfg = configs.get(UI_KEY);

        String template = cfg.getTemplates().get(templateKey);
        if (template == null) {
            return mm.deserialize(cfg.getPrefix() + "<red>Missing template: <white>" + templateKey + "</white></red>");
        }

        String full = cfg.getPrefix() + template;
        return mm.deserialize(full, toResolver(tokens.asMap()));
    }

    @Override
    public Component render(String templateKey, String... args) {
        List<String> argNames = expectedArgs(templateKey);

        if (args.length < argNames.size()) {
            String msg = "<red>Template '<white>" + templateKey + "</white>' requires "
                    + argNames.size() + " args: <white>" + String.join(", ", argNames) + "</white></red>";
            return mm.deserialize(msg);
        }

        Map<String, String> mapped = new LinkedHashMap<>();
        for (int i = 0; i < argNames.size(); i++) {
            String value = (i == argNames.size() - 1)
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, i, args.length))
                    : args[i];
            mapped.put(argNames.get(i), value);
        }

        UiTokens tokens = new UiTokens();
        mapped.forEach(tokens::put);
        return render(templateKey, tokens);
    }

    @Override
    public void send(CommandSender sender, String templateKey) {
        send(sender, templateKey, new UiTokens());
    }

    @Override
    public void send(CommandSender sender, String templateKey, UiTokens tokens) {
        sender.sendMessage(render(templateKey, tokens));
    }

    @Override
    public void send(CommandSender sender, String templateKey, String... args) {
        sender.sendMessage(render(templateKey, args));
    }

    @Override
    public void broadcast(String templateKey) {
        broadcast(templateKey, new UiTokens());
    }

    @Override
    public void broadcast(String templateKey, UiTokens tokens) {
        Component c = render(templateKey, tokens);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
        Bukkit.getConsoleSender().sendMessage(c);
    }

    @Override
    public void broadcast(String templateKey, String... args) {
        Component c = render(templateKey, args);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
        Bukkit.getConsoleSender().sendMessage(c);
    }

    @Override
    public List<String> expectedArgs(String templateKey) {
        UiConfig cfg = configs.get(UI_KEY);
        List<String> names = cfg.getTemplateArgs().get(templateKey);
        return names == null ? List.of() : List.copyOf(names);
    }

    @Override
    public Set<String> templates() {
        UiConfig cfg = configs.get(UI_KEY);
        return Set.copyOf(cfg.getTemplates().keySet());
    }

    private TagResolver toResolver(Map<String, String> tokens) {
        TagResolver.Builder b = TagResolver.builder();
        for (var e : tokens.entrySet()) {
            b.resolver(Placeholder.unparsed(e.getKey(), e.getValue()));
        }
        return b.build();
    }
}

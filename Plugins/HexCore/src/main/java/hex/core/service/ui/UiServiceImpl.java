package hex.core.service.ui;

import hex.core.api.config.ConfigKey;
import hex.core.api.config.ConfigService;
import hex.core.api.ui.TemplateDefinition;
import hex.core.api.ui.UiPreset;
import hex.core.api.ui.UiService;
import hex.core.api.ui.UiTokens;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UiServiceImpl implements UiService {

    public static final ConfigKey<UiConfig> UI_KEY = new ConfigKey<>("ui", UiConfig.class);

    private static final Duration DEFAULT_FADE_IN = Duration.ofMillis(200);
    private static final Duration DEFAULT_STAY = Duration.ofSeconds(2);
    private static final Duration DEFAULT_FADE_OUT = Duration.ofMillis(300);

    private final ConfigService configs;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final TemplateRegistry registry = new TemplateRegistry();
    private final Map<String, UiPreset> runtimePresets = new ConcurrentHashMap<>();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    public UiServiceImpl(ConfigService configs) {
        this.configs = configs;
    }

    @Override
    public void registerDefaults(String namespace, Map<String, String> templates) {
        registry.registerDefaults(namespace, templates);
    }

    @Override
    public void registerDefaultsWithArgs(String namespace, Map<String, TemplateDefinition> templates) {
        registry.registerDefaultsWithArgs(namespace, templates);
    }

    @Override
    public void registerPreset(String presetId, UiPreset preset) {
        if (presetId == null || presetId.isBlank() || preset == null) {
            return;
        }
        runtimePresets.put(presetId, preset);
    }

    @Override
    public Component render(String templateKey) {
        return render(templateKey, new UiTokens());
    }

    @Override
    public Component render(String templateKey, UiTokens tokens) {
        return renderInternal(templateKey, tokens, true);
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
        sendConsole(c);
    }

    @Override
    public void broadcast(String templateKey, String... args) {
        Component c = render(templateKey, args);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(c));
        sendConsole(c);
    }

    @Override
    public void sendActionBar(Player player, String templateKey) {
        sendActionBar(player, templateKey, new UiTokens());
    }

    @Override
    public void sendActionBar(Player player, String templateKey, UiTokens tokens) {
        if (player == null) {
            return;
        }
        player.sendActionBar(renderInternal(templateKey, tokens, false));
    }

    @Override
    public void broadcastActionBar(String templateKey, UiTokens tokens) {
        Component c = renderInternal(templateKey, tokens, false);
        Bukkit.getOnlinePlayers().forEach(p -> p.sendActionBar(c));
    }

    @Override
    public void sendTitle(Player player, String titleKey, String subtitleKey, UiTokens tokens) {
        sendTitle(player, titleKey, subtitleKey, tokens, DEFAULT_FADE_IN, DEFAULT_STAY, DEFAULT_FADE_OUT);
    }

    @Override
    public void sendTitle(Player player, String titleKey, String subtitleKey, UiTokens tokens,
                          Duration fadeIn, Duration stay, Duration fadeOut) {
        if (player == null) {
            return;
        }

        Component title = titleKey == null || titleKey.isBlank()
                ? Component.empty()
                : renderInternal(titleKey, tokens, false);

        Component subtitle = subtitleKey == null || subtitleKey.isBlank()
                ? Component.empty()
                : renderInternal(subtitleKey, tokens, false);

        player.showTitle(Title.title(title, subtitle, Title.Times.times(fadeIn, stay, fadeOut)));
    }

    @Override
    public void broadcastTitle(String titleKey, String subtitleKey, UiTokens tokens) {
        Bukkit.getOnlinePlayers().forEach(p -> sendTitle(p, titleKey, subtitleKey, tokens));
    }

    @Override
    public void playSound(Player player, Sound sound) {
        if (player == null || sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    @Override
    public void broadcastSound(Sound sound) {
        if (sound == null) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), sound, 1.0f, 1.0f));
    }

    @Override
    public void sendPreset(Player target, String presetId, UiTokens tokens) {
        if (target == null) {
            return;
        }

        UiPreset preset = resolvePreset(presetId);
        if (preset == null) {
            return;
        }

        UiTokens safeTokens = tokens == null ? new UiTokens() : tokens;

        if (preset.chatTemplateKey() != null && !preset.chatTemplateKey().isBlank()) {
            if (preset.chatBroadcast()) {
                broadcast(preset.chatTemplateKey(), safeTokens);
            } else {
                send(target, preset.chatTemplateKey(), safeTokens);
            }
        }

        if (preset.actionbarTemplateKey() != null && !preset.actionbarTemplateKey().isBlank()) {
            if (preset.actionbarBroadcast()) {
                broadcastActionBar(preset.actionbarTemplateKey(), safeTokens);
            } else {
                sendActionBar(target, preset.actionbarTemplateKey(), safeTokens);
            }
        }

        if (preset.titleTemplateKey() != null || preset.subtitleTemplateKey() != null) {
            if (preset.titleBroadcast()) {
                broadcastTitle(preset.titleTemplateKey(), preset.subtitleTemplateKey(), safeTokens);
            } else {
                sendTitle(target, preset.titleTemplateKey(), preset.subtitleTemplateKey(), safeTokens);
            }
        }

        if (preset.sound() != null && !preset.sound().isBlank()) {
            Sound sound = parseSound(preset.sound());
            if (sound != null) {
                if (preset.soundBroadcast()) {
                    Bukkit.getOnlinePlayers().forEach(p ->
                            p.playSound(p.getLocation(), sound, preset.soundVolume(), preset.soundPitch()));
                } else {
                    target.playSound(target.getLocation(), sound, preset.soundVolume(), preset.soundPitch());
                }
            }
        }
    }

    @Override
    public void broadcastPreset(String presetId, UiTokens tokens) {
        UiPreset preset = resolvePreset(presetId);
        if (preset == null) {
            return;
        }

        UiTokens safeTokens = tokens == null ? new UiTokens() : tokens;

        if (preset.chatTemplateKey() != null && !preset.chatTemplateKey().isBlank()) {
            broadcast(preset.chatTemplateKey(), safeTokens);
        }

        if (preset.actionbarTemplateKey() != null && !preset.actionbarTemplateKey().isBlank()) {
            broadcastActionBar(preset.actionbarTemplateKey(), safeTokens);
        }

        if (preset.titleTemplateKey() != null || preset.subtitleTemplateKey() != null) {
            broadcastTitle(preset.titleTemplateKey(), preset.subtitleTemplateKey(), safeTokens);
        }

        if (preset.sound() != null && !preset.sound().isBlank()) {
            Sound sound = parseSound(preset.sound());
            if (sound != null) {
                Bukkit.getOnlinePlayers().forEach(p ->
                        p.playSound(p.getLocation(), sound, preset.soundVolume(), preset.soundPitch()));
            }
        }
    }

    @Override
    public List<String> expectedArgs(String templateKey) {
        UiConfig cfg = configs.get(UI_KEY);
        return registry.resolveArgs(cfg, templateKey);
    }

    @Override
    public Set<String> templates() {
        UiConfig cfg = configs.get(UI_KEY);
        return registry.allKeys(cfg);
    }

    @Override
    public Set<String> templates(String namespace) {
        UiConfig cfg = configs.get(UI_KEY);
        return registry.keysForNamespace(cfg, namespace);
    }

    private Component renderInternal(String templateKey, UiTokens tokens, boolean withPrefix) {
        UiConfig cfg = configs.get(UI_KEY);

        String template = registry.resolveTemplate(cfg, templateKey);
        if (template == null) {
            String missingPrefix = withPrefix ? cfg.getPrefix() : "";
            return mm.deserialize(missingPrefix + "<red>Missing template: <white>" + templateKey + "</white></red>");
        }

        String full = (withPrefix ? resolvePrefix(cfg, templateKey) : "") + template;
        return mm.deserialize(full, toResolver(tokens == null ? Map.of() : tokens.asMap()));
    }

    private String resolvePrefix(UiConfig cfg, String templateKey) {
        String global = cfg.getPrefix() == null ? "" : cfg.getPrefix();
        if (templateKey == null) {
            return global;
        }

        int idx = templateKey.indexOf('.');
        if (idx <= 0) {
            return global;
        }

        String namespace = templateKey.substring(0, idx);
        String nsPrefix = cfg.getPrefixes().get(namespace);
        return nsPrefix == null ? global : nsPrefix;
    }

    private UiPreset resolvePreset(String presetId) {
        if (presetId == null || presetId.isBlank()) {
            return null;
        }

        UiPreset runtime = runtimePresets.get(presetId);
        if (runtime != null) {
            return runtime;
        }

        UiConfig cfg = configs.get(UI_KEY);
        UiPresetConfig fromConfig = cfg.getPresets().get(presetId);
        if (fromConfig == null) {
            return null;
        }

        UiPreset.Builder builder = UiPreset.builder()
                .soundVolume(fromConfig.getVolume())
                .soundPitch(fromConfig.getPitch());

        if (fromConfig.getChat() != null && !fromConfig.getChat().isBlank()) {
            if (fromConfig.isChatBroadcast()) {
                builder.broadcastChat(fromConfig.getChat());
            } else {
                builder.chat(fromConfig.getChat());
            }
        }

        if (fromConfig.getActionbar() != null && !fromConfig.getActionbar().isBlank()) {
            if (fromConfig.isActionbarBroadcast()) {
                builder.broadcastActionbar(fromConfig.getActionbar());
            } else {
                builder.actionbar(fromConfig.getActionbar());
            }
        }

        if ((fromConfig.getTitle() != null && !fromConfig.getTitle().isBlank())
                || (fromConfig.getSubtitle() != null && !fromConfig.getSubtitle().isBlank())) {
            if (fromConfig.isTitleBroadcast()) {
                builder.broadcastTitle(fromConfig.getTitle(), fromConfig.getSubtitle());
            } else {
                builder.title(fromConfig.getTitle(), fromConfig.getSubtitle());
            }
        }

        if (fromConfig.getSound() != null && !fromConfig.getSound().isBlank()) {
            if (fromConfig.isSoundBroadcast()) {
                builder.broadcastSound(fromConfig.getSound());
            } else {
                builder.sound(fromConfig.getSound());
            }
        }

        return builder.build();
    }

    private Sound parseSound(String soundName) {
        try {
            return Sound.valueOf(soundName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void sendConsole(Component component) {
        Bukkit.getConsoleSender().sendMessage(plain.serialize(component));
    }

    private TagResolver toResolver(Map<String, String> tokens) {
        TagResolver.Builder b = TagResolver.builder();
        for (var e : tokens.entrySet()) {
            b.resolver(Placeholder.unparsed(e.getKey(), e.getValue()));
        }
        return b.build();
    }
}

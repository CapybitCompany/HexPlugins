package hex.minigames.game.common;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.RoundContext;
import hex.minigames.util.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StandardTutorial {
    private final Plugin plugin;
    private final TutorialSettings settings;
    private final Set<UUID> blocked = new HashSet<>();

    public StandardTutorial(Plugin plugin, TutorialSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public int durationSeconds() {
        return settings.durationSeconds();
    }

    public void begin(RoundContext context, Collection<Player> players, Map<String, String> placeholders) {
        for (Player player : players) {
            for (String line : settings.chatLines()) {
                if ("hot_head".equals(context.definition().id())) line = line.replace("2 minuty", "{duration} sekund");
                if ("popcorn".equals(context.definition().id())) line = line.replace("90 sekund", "{duration} sekund");
                player.sendMessage(Text.color(apply(line, placeholders)));
            }
            context.blockChat(player.getUniqueId());
            blocked.add(player.getUniqueId());
            sendTitle(player, settings.durationSeconds(), placeholders);
        }
    }

    public boolean tick(RoundContext context, int ticksRemaining, Map<String, String> placeholders) {
        int seconds = Math.max(0, ticksRemaining / 20);
        for (Player player : context.onlineParticipants()) {
            if (!blocked.contains(player.getUniqueId())) continue;
            sendTitle(player, seconds, placeholders);
            if (shouldPlayCountdownSound(seconds, settings)) {
                play(player, settings.countdownSound());
            }
        }
        return true;
    }

    public static boolean shouldPlayCountdownSound(int seconds, TutorialSettings settings) {
        return settings != null && seconds >= 1 && seconds <= settings.soundFromSeconds();
    }

    public void end(RoundContext context) {
        for (UUID playerId : Set.copyOf(blocked)) {
            context.unblockChat(playerId);
        }
        blocked.clear();
    }

    public void remove(RoundContext context, UUID playerId) {
        if (!blocked.remove(playerId)) return;
        context.unblockChat(playerId);
    }

    private void sendTitle(Player player, int seconds, Map<String, String> placeholders) {
        String subtitle = apply(settings.subtitle().replace("{seconds}", String.valueOf(seconds)), placeholders);
        player.sendTitle(
                Text.color(apply(settings.title(), placeholders)),
                Text.color(subtitle),
                0,
                settings.titleStayTicks(),
                0
        );
    }

    private void play(Player player, ConfiguredSound configured) {
        if (configured == null || !configured.enabled()) return;
        Sound sound;
        try {
            sound = configured.bukkitSound();
        } catch (Throwable error) {
            plugin.getLogger().warning("Invalid tutorial sound: " + configured.sound());
            return;
        }
        if (sound != null) player.playSound(player.getLocation(), sound, configured.volume(), configured.pitch());
    }

    private String apply(String input, Map<String, String> placeholders) {
        String out = input == null ? "" : input;
        if (placeholders == null) return out;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }
}

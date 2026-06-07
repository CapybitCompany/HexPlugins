package hex.vishopbroadcast.config;

import java.util.List;

public record ServiceDisplay(
        List<DisplayChannel> channels,
        int durationSeconds,
        List<String> chatLines,
        String actionbar,
        String title,
        String subtitle,
        int titleFadeInTicks,
        int titleStayTicks,
        int titleFadeOutTicks
) {
    public boolean chatOnly() {
        return channels.size() == 1 && channels.contains(DisplayChannel.CHAT);
    }

    public boolean hasChannel(DisplayChannel channel) {
        return channels.contains(channel);
    }
}


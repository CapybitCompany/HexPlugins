package hex.minigames.config;

import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

public record PregameConfig(
        boolean enabled,
        CuboidRegion region,
        LocationSpec spawn,
        int minimumPlayers,
        int drawDurationSeconds,
        int countdownSeconds,
        String bossBarTitle,
        BarColor bossBarColor,
        BarStyle bossBarStyle,
        ConfiguredSound countdownSound
) {
}

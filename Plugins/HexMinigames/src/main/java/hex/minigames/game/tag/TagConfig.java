package hex.minigames.game.tag;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.*;
import hex.minigames.model.CuboidRegion;
import java.util.*;

/** Configurable pacing, messages and scoring thresholds for tag. */
public record TagConfig(CuboidRegion region, int durationSeconds, TutorialSettings tutorial,
        BossBarSettings bossBar, int immunityTicks, List<Integer> pointThresholdsSeconds) {
    public static final String ID = "tag";

    public TagConfig { pointThresholdsSeconds = List.copyOf(pointThresholdsSeconds); }

    public static TagConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        var settings = definition.settings();
        var region = definition.region().orElse(null);
        if (region == null) errors.add("tag: region must be configured");
        List<Integer> thresholds = new ArrayList<>();
        Object raw = GameSettings.child(settings, "scoring.runner-seconds");
        if (raw == null) thresholds.addAll(List.of(30, 75, 120));
        else for (Object item : GameSettings.list(raw)) thresholds.add(GameSettings.intValue(item, -1));
        if (thresholds.isEmpty() || thresholds.stream().anyMatch(value -> value <= 0)
                || new HashSet<>(thresholds).size() != thresholds.size()) errors.add("tag: scoring.runner-seconds must contain distinct positive thresholds");
        return new TagConfig(region, definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, definition.sourcePath(), List.of("&d&lBEREK",
                        "&fCzerwony obrys = berek, zielony = uciekający.",
                        "&fBerek przekazuje swoją rolę uderzeniem ręką.",
                        "&fPo oddaniu berka masz 2 sekundy ochrony.",
                        "&fPunkty zdobywasz za łączny czas jako uciekający."), errors),
                CommonGameConfig.bossBar(settings, definition.sourcePath(), "&d&lBEREK", errors),
                Math.max(1, GameSettings.integer(settings, "immunity-ticks", 40)), thresholds);
    }
}

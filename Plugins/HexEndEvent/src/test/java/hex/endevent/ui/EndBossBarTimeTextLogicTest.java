package hex.endevent.ui;

import hex.endevent.util.TimeTextFormatter;
import java.time.Duration;

public final class EndBossBarTimeTextLogicTest {
    public static void main(String[] args) {
        String text = TimeTextFormatter.duration(Duration.ofHours(1).plusMinutes(12).plusSeconds(37));
        if (!"1h 12m 37s".equals(text)) throw new AssertionError("unexpected countdown: " + text);
        String shortText = TimeTextFormatter.duration(Duration.ofMinutes(4).plusSeconds(9));
        if (!"4m 9s".equals(shortText)) throw new AssertionError("unexpected short countdown: " + shortText);
        System.out.println("EndBossBarTimeTextLogicTest OK");
    }
}

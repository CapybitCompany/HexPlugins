package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja Part B #6/#13: filler-glass-pane w GUI Bazaru i Aukcji nie
 * powinien pokazywac pustego prostokata tooltip. Wymuszamy w GuiFrame
 * uzycie ItemFlag.HIDE_TOOLTIP lub odpowiednika (reflection).
 * Test zrodlowy - nie odpalamy Bukkitu, tylko sprawdzamy, ze kod
 * ktory ma to zapewnic istnieje.
 */
class GuiFillerTooltipTest {

    @Test
    void guiFrameFillerCallsHideTooltipHelper() throws IOException {
        Path p = Path.of("src/main/java/hex/auctionbazaar/gui/GuiFrame.java");
        String text = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(text.contains("applyHideTooltip("),
                "GuiFrame.filler musi wywolywac applyHideTooltip");
        assertTrue(text.contains("HIDE_TOOLTIP"),
                "GuiFrame musi uzywac ItemFlag.HIDE_TOOLTIP lub odpowiednika");
        assertTrue(text.contains("setHideTooltip"),
                "GuiFrame musi probowac Paper meta.setHideTooltip via reflection");
    }
}

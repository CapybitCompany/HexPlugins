package hex.towns.map;

import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapFont;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.util.Map;
import java.util.UUID;

final class TownMapRenderer extends MapRenderer {
    private static final Color BACKGROUND = new Color(38, 38, 38);
    private static final Color GRID = new Color(82, 82, 82);
    private static final Color OWN = new Color(40, 190, 80);
    private static final Color OTHER = new Color(210, 70, 70);
    private static final Color PLAYER = new Color(255, 230, 80);
    private static final Color TEXT = new Color(250, 250, 250);

    private final TownsService service;
    private final String world;
    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final UUID ownTownId;
    private final Map<UUID, Town> nearby;
    private boolean rendered;

    TownMapRenderer(TownsService service, String world, int centerX, int centerZ, int radius, UUID ownTownId, Map<UUID, Town> nearby) {
        super(false);
        this.service = service;
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.ownTownId = ownTownId;
        this.nearby = nearby;
    }

    @Override
    public void render(@NotNull MapView view, @NotNull MapCanvas canvas, @NotNull Player player) {
        if (rendered) {
            return;
        }
        rendered = true;
        fill(canvas, BACKGROUND);
        int diameter = radius * 2 + 1;
        int cell = Math.max(4, Math.min(12, 104 / diameter));
        int startX = (128 - diameter * cell) / 2;
        int startY = 18;
        drawTitle(canvas, "HexTowns", 4, 2);
        drawTitle(canvas, "X=" + centerX + " Z=" + centerZ, 4, 11);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                int px = startX + (dx + radius) * cell;
                int py = startY + (dz + radius) * cell;
                UUID townId = service.townIdAt(world, chunkX, chunkZ).orElse(null);
                Color color = townId == null ? GRID : (townId.equals(ownTownId) ? OWN : OTHER);
                drawCell(canvas, px, py, cell, color);
                if (dx == 0 && dz == 0) {
                    drawPlayer(canvas, px, py, cell);
                }
            }
        }
        int y = 108;
        int index = 1;
        for (Town town : nearby.values()) {
            if (y > 124 || index > 4) {
                break;
            }
            String prefix = ownTownId != null && ownTownId.equals(town.id()) ? "* " : "- ";
            drawText(canvas, prefix + trim(town.name(), 16), 4, y);
            y += 8;
            index++;
        }
    }

    public boolean isExplorerMap() {
        return false;
    }

    private void fill(MapCanvas canvas, Color color) {
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                canvas.setPixelColor(x, y, color);
            }
        }
    }

    private void drawCell(MapCanvas canvas, int x, int y, int size, Color color) {
        for (int px = x; px < x + size; px++) {
            for (int py = y; py < y + size; py++) {
                if (px >= 0 && px < 128 && py >= 0 && py < 128) {
                    canvas.setPixelColor(px, py, px == x || py == y || px == x + size - 1 || py == y + size - 1 ? TEXT : color);
                }
            }
        }
    }

    private void drawPlayer(MapCanvas canvas, int x, int y, int size) {
        int midX = x + size / 2;
        int midY = y + size / 2;
        for (int i = -1; i <= 1; i++) {
            set(canvas, midX + i, midY, PLAYER);
            set(canvas, midX, midY + i, PLAYER);
        }
    }

    private void set(MapCanvas canvas, int x, int y, Color color) {
        if (x >= 0 && x < 128 && y >= 0 && y < 128) {
            canvas.setPixelColor(x, y, color);
        }
    }

    private void drawTitle(MapCanvas canvas, String text, int x, int y) {
        drawText(canvas, text, x, y);
    }

    private void drawText(MapCanvas canvas, String text, int x, int y) {
        MapFont font = MinecraftFont.Font;
        canvas.drawText(x, y, font, text);
    }

    private String trim(String value, int max) {
        if (value == null) {
            return "Town";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}

package hex.auctionbazaar.gui;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Wybór bezpiecznej, załadowanej i możliwej do zastąpienia lokalizacji dla
 * tymczasowej tabliczki (punkt #1).
 *
 * Reguły:
 *  - preferujemy pozycję ok. 2 bloki nad graczem, w bezpośrednim zasięgu,
 *  - nigdy nie nadpisujemy wartościowego/zajętego bloku - tylko powietrze lub
 *    lekkie bloki zastępowalne (trawa, paproć, śnieg, pnącza),
 *  - pozycja zawsze walidowana względem minHeight/maxHeight,
 *  - gdy żaden kandydat nie jest bezpieczny -> pusty Optional (caller robi
 *    natychmiastowy fallback na czat).
 *
 * Metody sprawdzające ({@link #isReplaceable}, {@link #withinBounds}) są czyste
 * i łatwo testowalne bez serwera.
 */
public final class SignLocationPicker {

    /** Bloki bezpieczne do chwilowego zastąpienia (poza czystym powietrzem). */
    private static final Set<Material> REPLACEABLE = EnumSet.of(
            Material.SHORT_GRASS,
            Material.TALL_GRASS,
            Material.FERN,
            Material.LARGE_FERN,
            Material.SNOW,
            Material.VINE,
            Material.DEAD_BUSH
    );

    /**
     * Kandydaci względem bloku stóp gracza (dx, dy, dz). Kolejność = priorytet;
     * najpierw ~2 bloki nad graczem, potem najbliższe sąsiedztwo. Wszyscy w
     * bezpośrednim zasięgu (|dy| <= 3, |dx|,|dz| <= 1).
     */
    private static final int[][] OFFSETS = {
            {0, 2, 0},
            {0, 3, 0},
            {0, 1, 0},
            {1, 2, 0},
            {-1, 2, 0},
            {0, 2, 1},
            {0, 2, -1}
    };

    private SignLocationPicker() {
    }

    public static boolean isReplaceable(Material material) {
        if (material == null) return false;
        return material.isAir() || REPLACEABLE.contains(material);
    }

    public static boolean withinBounds(int y, int minHeight, int maxHeight) {
        // maxHeight jest ekskluzywne (world.getMaxHeight() to pierwszy blok POZA światem).
        return y >= minHeight && y < maxHeight;
    }

    public static Optional<Location> pick(Player player) {
        if (player == null) return Optional.empty();
        World world = player.getWorld();
        if (world == null) return Optional.empty();
        Location base = player.getLocation();
        int baseX = base.getBlockX();
        int baseY = base.getBlockY();
        int baseZ = base.getBlockZ();
        int minH = world.getMinHeight();
        int maxH = world.getMaxHeight();

        for (int[] off : OFFSETS) {
            int x = baseX + off[0];
            int y = baseY + off[1];
            int z = baseZ + off[2];
            if (!withinBounds(y, minH, maxH)) continue;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block block = world.getBlockAt(x, y, z);
            if (!isReplaceable(block.getType())) continue;
            return Optional.of(block.getLocation());
        }
        return Optional.empty();
    }
}

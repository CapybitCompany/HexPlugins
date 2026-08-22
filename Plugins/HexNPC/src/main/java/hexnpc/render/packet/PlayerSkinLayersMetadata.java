package hexnpc.render.packet;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wersjonalna mapa indeksu pola entity-metadata zawierajacego "Displayed Skin Parts"
 * (maska bitowa peleryny, kurtki, rekawow, nogawek i kapelusza) dla encji gracza.
 *
 * <p>Indeks jest WRAZLIWY na wersje, bo Mojang przesunal pola w 1.21.9: pomiedzy
 * {@code LivingEntity} a {@code Player} wstawiono nowa klase {@code Avatar} (wspolne
 * cialo dla nowej encji Mannequin, dodanej w snapshocie 25w36a). Skutek:
 * <ul>
 *   <li>1.21.0 - 1.21.8 (protocol 767 - 772): {@code Displayed Skin Parts} (Byte) na indeksie 17,
 *       {@code Additional Hearts} (Float) na indeksie 15.</li>
 *   <li>1.21.9 - 1.21.11 (protocol 773 - 774): {@code Displayed Skin Parts} (Byte) przeniesione
 *       do Avatar pod indeks 16, a Player na indeksie 17 ma teraz {@code Additional Hearts} (Float).</li>
 * </ul>
 *
 * <p>Wyslanie BYTE pod indeks 17 do klienta 1.21.9+ powoduje natychmiastowy disconnect:
 * {@code Invalid entity data item type for field 17 ... old=0.0(Float), new=127(Byte)}.
 *
 * <p>Zrodlo: <a href="https://minecraft.wiki/w/Java_Edition_protocol/Entity_metadata">Minecraft
 * Wiki - Java Edition protocol / Entity metadata</a> (Avatar: index 16 Byte; Player: index 17 Float).
 *
 * <p>Dla nieznanych/nieprzetestowanych wersji metoda zwraca {@link Optional#empty()} - lepsze
 * brakujace warstwy zewnetrzne skinu niz wykopany klient.
 */
public final class PlayerSkinLayersMetadata {

    /** "Wszystkie warstwy skinu wlaczone" - maska niezalezna od wersji protokolu. */
    public static final byte ALL_LAYERS_MASK = (byte) 0x7F;

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private PlayerSkinLayersMetadata() {
    }

    /**
     * Zwraca indeks pola entity-metadata pod ktorym dla danej wersji Minecrafta nalezy
     * wyslac BYTE z maska widocznych warstw skinu, lub {@link Optional#empty()} jezeli
     * wersja nie jest objeta sprawdzona mapa.
     *
     * @param minecraftVersion ciag w stylu {@code "1.21.11"} - typowo
     *                         {@code Bukkit.getServer().getMinecraftVersion()}
     */
    public static Optional<Integer> resolve(String minecraftVersion) {
        if (minecraftVersion == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(minecraftVersion.trim());
        if (!m.find()) {
            return Optional.empty();
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        return resolve(major, minor, patch);
    }

    static Optional<Integer> resolve(int major, int minor, int patch) {
        if (major != 1) {
            return Optional.empty();
        }
        // Wczesniejsze niz 1.20 - layout sie wczesniej zmienial wielokrotnie i nie mamy
        // empirycznego potwierdzenia, wiec wolimy nie wysylac niz wykopac klienta.
        if (minor < 20) {
            return Optional.empty();
        }
        if (minor == 20) {
            // 1.20.x: Player.15 = Float (Additional Hearts), Player.17 = Byte (Displayed Skin Parts).
            return Optional.of(17);
        }
        if (minor == 21) {
            // 1.21.0 - 1.21.8: stary layout (Player.17 = Byte).
            // 1.21.9+: Avatar wstawiony, skin parts na 16; index 17 jest teraz Float.
            if (patch <= 8) {
                return Optional.of(17);
            }
            if (patch <= 11) {
                return Optional.of(16);
            }
            return Optional.empty();
        }
        // 1.22+ - nieznane, moze sie zmienic ponownie; nie ryzykujemy.
        return Optional.empty();
    }
}

package hexnpc.shop.sign;

import org.bukkit.entity.Player;

/**
 * Warstwa transportu wirtualnej tabliczki — celowo bez typów PacketEvents,
 * dzięki czemu {@link SignInputService} nie musi ładować PacketEvents i jest
 * w pełni testowalny (można podstawić atrapę rejestrującą kolejność wywołań i
 * symulującą błąd na dowolnym etapie).
 */
public interface SignTransport {

    /** Etap, do którego udało się dojść przy otwieraniu edytora. */
    enum Stage {
        /** Nic nie wysłano (transport niedostępny / brak id bloku). */
        NONE,
        /** Wysłano fałszywy blok (klient ma już ghost-blok do posprzątania). */
        BLOCK_CHANGE,
        /** Wysłano też dane block-entity tabliczki. */
        BLOCK_ENTITY,
        /** Wysłano wszystkie 3 pakiety — edytor w pełni zażądany. */
        OPEN_EDITOR
    }

    /**
     * Wynik próby otwarcia edytora. Rozróżnia jak daleko doszedł wysył pakietów,
     * dzięki czemu {@link SignInputService} wie, czy istnieje ghost-blok do
     * przywrócenia i czy tryb sign faktycznie jest aktywny.
     */
    record OpenResult(Stage stage, String reason) {

        public static OpenResult none(String reason) {
            return new OpenResult(Stage.NONE, reason);
        }

        /** Czy klient dostał fałszywy blok (wymaga przywrócenia realnego). */
        public boolean fakeBlockSent() {
            return stage != Stage.NONE;
        }

        /** Czy edytor został w pełni zażądany (wszystkie 3 pakiety poszły). */
        public boolean opened() {
            return stage == Stage.OPEN_EDITOR;
        }
    }

    /** Czy transport (np. PacketEvents) jest w ogóle dostępny. */
    boolean isAvailable();

    /**
     * Wysyła klientowi wirtualną tabliczkę i otwiera edytor na podanej, czysto
     * klienckiej pozycji. Zwraca etap, do którego doszedł wysył. Sukces wymaga
     * WSZYSTKICH etapów. Uwaga: pełny sukces to nadal nie dowód, że klient
     * otworzył edytor — dlatego istnieje równoległy fallback na czat.
     */
    OpenResult openEditor(Player player, int x, int y, int z);

    /**
     * Przywraca po stronie klienta realny stan bloku na danej pozycji (usuwa
     * ghost-blok tabliczki). Wołane przy każdym zakończeniu, gdy fałszywy blok
     * został wysłany. Nie może zmieniać realnego bloku w świecie.
     */
    void restore(Player player, int x, int y, int z);

    /** Transport „niedostępny" — wymusza fallback na czat. */
    static SignTransport unavailable() {
        return new SignTransport() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public OpenResult openEditor(Player player, int x, int y, int z) {
                return OpenResult.none("transport niedostępny");
            }

            @Override
            public void restore(Player player, int x, int y, int z) {
                // Nic nie wysłano — nie ma czego przywracać.
            }
        };
    }
}

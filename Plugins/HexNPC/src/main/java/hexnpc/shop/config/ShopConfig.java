package hexnpc.shop.config;

import hexnpc.shop.model.ShopLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ShopConfig(
        boolean enabled,
        boolean requireEconomy,
        String titleFormat,
        boolean preventSellingCustomItems,
        ShopLayout defaultLayout,
        List<Integer> quantityPresets,
        boolean enableCustomQuantity,
        boolean enableSellAll,
        boolean signEnabled,
        int signTimeoutSeconds,
        int signFailoverSeconds,
        int priceScale,
        Confirmation confirmation,
        AuditLog auditLog,
        ShopMessages messages
) {
    /** Górny bezpiecznik na wybraną ilość w jednej transakcji (anty-overflow). */
    public static final int MAX_QUANTITY = 100_000;

    public ShopConfig {
        titleFormat = Objects.toString(titleFormat, "&8Sklep: &6<shop>");
        defaultLayout = defaultLayout == null ? ShopLayout.defaults(54) : defaultLayout;
        quantityPresets = sanitizePresets(quantityPresets);
        if (signTimeoutSeconds < 1 || signTimeoutSeconds > 300) {
            signTimeoutSeconds = 30;
        }
        // Failover czatu musi zmieścić się przed twardym timeoutem.
        if (signFailoverSeconds < 1 || signFailoverSeconds > signTimeoutSeconds) {
            signFailoverSeconds = Math.min(4, signTimeoutSeconds);
        }
        if (priceScale < 0 || priceScale > 8) {
            priceScale = 2;
        }
        confirmation = confirmation == null ? Confirmation.defaults() : confirmation;
        auditLog = auditLog == null ? AuditLog.defaults() : auditLog;
        messages = messages == null ? ShopMessages.defaults() : messages;
    }

    private static List<Integer> sanitizePresets(List<Integer> raw) {
        List<Integer> out = new ArrayList<>();
        if (raw != null) {
            for (Integer value : raw) {
                if (value == null) {
                    continue;
                }
                int v = value;
                if (v >= 1 && v <= MAX_QUANTITY && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(1);
            out.add(64);
        }
        out.sort(Integer::compareTo);
        return List.copyOf(out);
    }

    /** Domyślny rozmiar GUI (z domyślnego układu) — kompatybilność wstecz. */
    public int defaultSize() {
        return defaultLayout.size();
    }

    /** Domyślny slot sprzedaży w widoku szczegółów — kompatybilność wstecz. */
    public int defaultSellSlot() {
        return defaultLayout.detailSellSlot();
    }

    public static ShopConfig defaults() {
        return new ShopConfig(
                true, true, "&8Sklep: &6<shop>", true,
                ShopLayout.defaults(54), List.of(1, 64), true, true,
                true, 30, 4, 2, Confirmation.defaults(), AuditLog.defaults(),
                ShopMessages.defaults());
    }

    /**
     * Potwierdzenie transakcji powyżej progu. {@code threshold=64} oznacza
     * potwierdzenie dopiero od 65 sztuk. Sloty są sanityzowane (w zakresie i
     * rozłączne) z bezpiecznymi wartościami domyślnymi.
     */
    public record Confirmation(
            boolean enabled,
            int threshold,
            int size,
            int previewSlot,
            int confirmSlot,
            int cancelSlot
    ) {
        public Confirmation {
            if (threshold < 0) {
                threshold = 64;
            }
            if (size <= 0 || size % 9 != 0 || size > 54) {
                size = 27;
            }
            int[] fixed = sanitizeSlots(size, previewSlot, confirmSlot, cancelSlot);
            previewSlot = fixed[0];
            confirmSlot = fixed[1];
            cancelSlot = fixed[2];
        }

        public static Confirmation defaults() {
            return new Confirmation(true, 64, 27, 13, 11, 15);
        }

        /** Czy transakcja o tej ilości wymaga potwierdzenia. */
        public boolean requiresConfirmation(int quantity) {
            return enabled && quantity > threshold;
        }

        private static int[] sanitizeSlots(int size, int preview, int confirm, int cancel) {
            Set<Integer> used = new LinkedHashSet<>();
            return new int[]{
                    claim(preview, 13, size, used),
                    claim(confirm, 11, size, used),
                    claim(cancel, 15, size, used)
            };
        }

        private static int claim(int desired, int fallback, int size, Set<Integer> used) {
            int slot = (desired >= 0 && desired < size) ? desired : fallback;
            if (slot < 0 || slot >= size || used.contains(slot)) {
                for (int i = 0; i < size; i++) {
                    if (!used.contains(i)) {
                        slot = i;
                        break;
                    }
                }
            }
            used.add(slot);
            return slot;
        }
    }

    /**
     * Konfiguracja audytu transakcji do MySQL przez HexCore. Nazwa tabeli musi
     * pasować do {@code [A-Za-z0-9_]+}; w przeciwnym razie używany jest
     * bezpieczny default.
     */
    public record AuditLog(
            boolean enabled,
            String table,
            boolean logDenied
    ) {
        public static final String DEFAULT_TABLE = "npc_shop_audit";

        public AuditLog {
            table = sanitizeTable(table);
        }

        public static AuditLog defaults() {
            return new AuditLog(true, DEFAULT_TABLE, true);
        }

        /** True, jeśli nazwa tabeli z konfiguracji była nieprawidłowa. */
        public boolean isTableSanitized(String rawTable) {
            return rawTable != null && !rawTable.equals(table);
        }

        private static String sanitizeTable(String raw) {
            if (raw == null) {
                return DEFAULT_TABLE;
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || !trimmed.matches("[A-Za-z0-9_]+")) {
                return DEFAULT_TABLE;
            }
            return trimmed;
        }
    }
}

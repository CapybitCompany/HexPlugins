package hex.auctionbazaar.bazaar.repository;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Repozytorium orderbooka Bazaar (hex_bazaar_orders).
 * Wszystkie operacje modyfikujace status/pozostala ilosc dzialaja w
 * transakcjach - matching odbywa sie przez {@link #tryFillPortionTx}.
 */
public final class BazaarOrderRepository {

    private static final String TABLE = "hex_bazaar_orders";

    private final Db db;

    public BazaarOrderRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    private String t() {
        return db.t(TABLE);
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + t() + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "owner_uuid CHAR(36) NOT NULL," +
                "owner_name VARCHAR(32) NULL," +
                "item_key VARCHAR(128) NOT NULL," +
                "side VARCHAR(16) NOT NULL," +
                "amount_total BIGINT NOT NULL," +
                "amount_remaining BIGINT NOT NULL," +
                "price_per_unit DECIMAL(19,2) NOT NULL," +
                "reserved_money DECIMAL(19,2) NULL," +
                "state VARCHAR(24) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL," +
                "expires_at BIGINT NULL," +
                "KEY idx_owner (owner_uuid)," +
                "KEY idx_book_buy (item_key, side, state, price_per_unit DESC, id ASC)," +
                "KEY idx_book_sell (item_key, side, state, price_per_unit ASC, id ASC)," +
                "KEY idx_state (state)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** Wstaw nowe zlecenie (ACTIVE). Zwraca id nowego wpisu. */
    public long insert(UUID owner, String ownerName, String itemKey, OrderSide side,
                       long amountTotal, BigDecimal pricePerUnit, BigDecimal reservedMoney,
                       long now, Long expiresAt) {
        return db.tx(tx -> {
            tx.update("INSERT INTO " + tx.t(TABLE) + " (" +
                            "owner_uuid, owner_name, item_key, side, " +
                            "amount_total, amount_remaining, price_per_unit, reserved_money, " +
                            "state, created_at, updated_at, expires_at" +
                            ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    owner.toString(), ownerName, itemKey, side.name(),
                    amountTotal, amountTotal, pricePerUnit, reservedMoney,
                    OrderState.ACTIVE.name(), now, now, expiresAt);
            return tx.queryOne("SELECT LAST_INSERT_ID() AS id",
                    rs -> rs.getLong("id")).orElseThrow();
        });
    }

    public Optional<BazaarOrder> findById(long id) {
        return db.queryOne("SELECT * FROM " + t() + " WHERE id=?",
                BazaarOrderRepository::map, id);
    }

    public List<BazaarOrder> findByOwner(UUID owner, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE owner_uuid=? ORDER BY id DESC LIMIT ?",
                BazaarOrderRepository::map, owner.toString(), limit);
    }

    public List<BazaarOrder> findOpenByOwner(UUID owner, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE owner_uuid=? AND state IN (?, ?) ORDER BY id DESC LIMIT ?",
                BazaarOrderRepository::map, owner.toString(),
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name(),
                limit);
    }

    /**
     * Zwraca top N otwartych zlecen kupna dla danego przedmiotu w kolejnosci
     * dopasowania (najwyzsza cena, potem najstarsze id). Uzywane przez
     * mechanizm pre-scan do wyliczenia szacowanego przychodu przed egzekucja.
     */
    public List<BazaarOrder> topOpenBuyOrders(String itemKey, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE item_key=? AND side=? AND state IN (?, ?) " +
                        "ORDER BY price_per_unit DESC, id ASC LIMIT ?",
                BazaarOrderRepository::map, itemKey, OrderSide.BUY.name(),
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name(), limit);
    }

    /**
     * Zwraca top N otwartych ofert sprzedazy dla danego przedmiotu w kolejnosci
     * dopasowania (najnizsza cena, potem najstarsze id).
     */
    public List<BazaarOrder> topOpenSellOffers(String itemKey, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE item_key=? AND side=? AND state IN (?, ?) " +
                        "ORDER BY price_per_unit ASC, id ASC LIMIT ?",
                BazaarOrderRepository::map, itemKey, OrderSide.SELL.name(),
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name(), limit);
    }

    /**
     * Najlepsze aktywne zlecenie kupna dla danego przedmiotu:
     * najwyzsza cena, potem najstarsze id.
     */
    public Optional<BazaarOrder> peekBestBuyOrder(String itemKey) {
        return db.queryOne(
                "SELECT * FROM " + t() + " WHERE item_key=? AND side=? AND state IN (?, ?) " +
                        "ORDER BY price_per_unit DESC, id ASC LIMIT 1",
                BazaarOrderRepository::map, itemKey, OrderSide.BUY.name(),
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name());
    }

    /**
     * Najlepsze aktywne zlecenie sprzedazy dla danego przedmiotu:
     * najnizsza cena, potem najstarsze id.
     */
    public Optional<BazaarOrder> peekBestSellOffer(String itemKey) {
        return db.queryOne(
                "SELECT * FROM " + t() + " WHERE item_key=? AND side=? AND state IN (?, ?) " +
                        "ORDER BY price_per_unit ASC, id ASC LIMIT 1",
                BazaarOrderRepository::map, itemKey, OrderSide.SELL.name(),
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name());
    }

    /**
     * Atomowo zdejmij "fillAmount" z zlecenia. Zwraca true przy powodzeniu.
     * Jesli po zdjeciu ilosc == 0 - status ustawia sie na FILLED,
     * w przeciwnym razie PARTIALLY_FILLED. Uzywane w transakcji zewnetrznej.
     */
    public static boolean tryFillPortionTx(Db tx, long orderId, long fillAmount, long now) {
        String tbl = tx.t(TABLE);
        int updated = tx.update(
                "UPDATE " + tbl + " SET amount_remaining=amount_remaining-?, " +
                        "state=CASE WHEN amount_remaining-?<=0 THEN ? ELSE ? END, " +
                        "updated_at=? " +
                        "WHERE id=? AND amount_remaining>=? AND state IN (?, ?)",
                fillAmount, fillAmount, OrderState.FILLED.name(), OrderState.PARTIALLY_FILLED.name(),
                now, orderId, fillAmount,
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name());
        return updated == 1;
    }

    /**
     * Atomowo zmniejsz reserved_money o wskazana wartosc.
     * Uzywane po dopasowaniu do BUY-order-a - zablokowana kasa idzie do
     * sprzedajacego jako claim.
     */
    public static boolean tryConsumeReservedMoneyTx(Db tx, long orderId, BigDecimal amount) {
        String tbl = tx.t(TABLE);
        int updated = tx.update(
                "UPDATE " + tbl + " SET reserved_money=reserved_money-? " +
                        "WHERE id=? AND reserved_money>=?",
                amount, orderId, amount);
        return updated == 1;
    }

    /**
     * Atomowo anuluj zlecenie i wywolaj refundacje w tej samej transakcji.
     * Wymaga statusu ACTIVE lub PARTIALLY_FILLED. Zwraca snapshot stanu
     * po anulowaniu lub {@link Optional#empty()} gdy sie nie udalo.
     *
     * refundHook otrzymuje transakcyjny {@link Db} oraz snapshot i moze
     * bezpiecznie utworzyc claim/y bez ryzyka rozspojnienia stanu.
     * Jesli refundHook rzuci wyjatek, cala transakcja jest wycofywana
     * (zlecenie nie zostanie anulowane) - dzieki temu operator moze
     * ponowic anulowanie a srodki nigdy nie znikaja.
     */
    public Optional<BazaarOrder> tryCancelWithRefundTx(long orderId, UUID owner, long now,
                                                        java.util.function.BiConsumer<Db, BazaarOrder> refundHook) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(TABLE) + " SET state=?, updated_at=? " +
                            "WHERE id=? AND owner_uuid=? AND state IN (?, ?)",
                    OrderState.CANCELLED.name(), now, orderId, owner.toString(),
                    OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name());
            if (updated != 1) {
                return Optional.<BazaarOrder>empty();
            }
            Optional<BazaarOrder> snap = tx.queryOne("SELECT * FROM " + tx.t(TABLE) + " WHERE id=?",
                    BazaarOrderRepository::map, orderId);
            if (snap.isEmpty()) {
                return Optional.<BazaarOrder>empty();
            }
            // Refundacja w tej samej transakcji. Jesli hook rzuci wyjatek,
            // Db.tx wycofa cala transakcje.
            refundHook.accept(tx, snap.get());
            // Wyzeruj reserved_money w tej samej transakcji - dla BUY po refund.
            if (snap.get().side() == OrderSide.BUY) {
                tx.update("UPDATE " + tx.t(TABLE) + " SET reserved_money=0 WHERE id=?",
                        orderId);
            }
            return snap;
        });
    }

    /** Wyczysc reserved_money do zera po zaksiegowaniu zwrotu. */
    public boolean clearReservedMoney(long orderId) {
        int updated = db.update(
                "UPDATE " + t() + " SET reserved_money=0 WHERE id=?",
                orderId);
        return updated == 1;
    }

    /** Znajdz otwarte zlecenia ktore wygasly (expires_at != null i <= now). */
    public List<BazaarOrder> findOpenExpired(long nowMs, int limit) {
        return db.query(
                "SELECT * FROM " + t() + " WHERE state IN (?, ?) " +
                        "AND expires_at IS NOT NULL AND expires_at <= ? " +
                        "ORDER BY expires_at ASC LIMIT ?",
                BazaarOrderRepository::map,
                OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name(),
                nowMs, limit);
    }

    /**
     * Anuluj/wygasnij pojedyncze zlecenie z refundacja w tej samej transakcji.
     * Uzywane przez scanner wygasania - jesli refund hook rzuci wyjatek, zlecenie
     * pozostanie otwarte i sprawdza sie w nastepnym cyklu.
     */
    public Optional<BazaarOrder> tryExpireWithRefundTx(long orderId, long now,
                                                        BiConsumer<Db, BazaarOrder> refundHook) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(TABLE) + " SET state=?, updated_at=? " +
                            "WHERE id=? AND state IN (?, ?)",
                    OrderState.EXPIRED.name(), now, orderId,
                    OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name());
            if (updated != 1) {
                return Optional.<BazaarOrder>empty();
            }
            Optional<BazaarOrder> snap = tx.queryOne("SELECT * FROM " + tx.t(TABLE) + " WHERE id=?",
                    BazaarOrderRepository::map, orderId);
            if (snap.isEmpty()) return Optional.<BazaarOrder>empty();
            refundHook.accept(tx, snap.get());
            if (snap.get().side() == OrderSide.BUY) {
                tx.update("UPDATE " + tx.t(TABLE) + " SET reserved_money=0 WHERE id=?", orderId);
            }
            return snap;
        });
    }

    public int countOpenByOwner(UUID owner) {
        return db.queryOne(
                "SELECT COUNT(*) AS c FROM " + t() +
                        " WHERE owner_uuid=? AND state IN (?, ?)",
                rs -> rs.getInt("c"),
                owner.toString(), OrderState.ACTIVE.name(), OrderState.PARTIALLY_FILLED.name()
        ).orElse(0);
    }

    private static BazaarOrder map(ResultSet rs) throws SQLException {
        long expiresRaw = rs.getLong("expires_at");
        Long expires = rs.wasNull() ? null : expiresRaw;
        return new BazaarOrder(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getString("item_key"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("amount_total"),
                rs.getLong("amount_remaining"),
                rs.getBigDecimal("price_per_unit"),
                rs.getBigDecimal("reserved_money"),
                OrderState.valueOf(rs.getString("state")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                expires
        );
    }
}

package hex.auctionbazaar.bazaar.repository;

import hex.auctionbazaar.bazaar.model.BazaarStock;
import hex.core.api.db.Db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class BazaarStockRepository {

    private static final String STOCK_TABLE = "hex_bazaar_stock";
    private static final String TX_TABLE = "hex_bazaar_transactions";

    private final Db db;

    public BazaarStockRepository(Db db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    private String t() {
        return db.t(STOCK_TABLE);
    }

    public void ensureTable() {
        db.update("CREATE TABLE IF NOT EXISTS " + t() + " (" +
                "item_key VARCHAR(64) PRIMARY KEY," +
                "stock BIGINT NOT NULL," +
                "last_buy_price DECIMAL(19,2) NULL," +
                "last_sell_price DECIMAL(19,2) NULL," +
                "updated_at BIGINT NOT NULL" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        db.update("CREATE TABLE IF NOT EXISTS " + db.t(TX_TABLE) + " (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "player_uuid CHAR(36) NOT NULL," +
                "player_name VARCHAR(32) NULL," +
                "item_key VARCHAR(64) NOT NULL," +
                "side VARCHAR(8) NOT NULL," +
                "amount INT NOT NULL," +
                "unit_price DECIMAL(19,2) NOT NULL," +
                "total_price DECIMAL(19,2) NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "KEY idx_player (player_uuid)," +
                "KEY idx_item (item_key)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public Optional<BazaarStock> find(String itemKey) {
        return db.queryOne("SELECT * FROM " + t() + " WHERE item_key=?",
                BazaarStockRepository::map, itemKey);
    }

    public Map<String, BazaarStock> findAll() {
        List<BazaarStock> all = db.query("SELECT * FROM " + t(), BazaarStockRepository::map);
        Map<String, BazaarStock> out = new LinkedHashMap<>();
        for (BazaarStock s : all) {
            out.put(s.itemKey(), s);
        }
        return out;
    }

    /** Idempotent insert of initial stock. */
    public void ensureInitialStock(String itemKey, long initialStock, long now) {
        db.update(
                "INSERT INTO " + t() + " (item_key, stock, last_buy_price, last_sell_price, updated_at) " +
                        " VALUES (?, ?, NULL, NULL, ?) ON DUPLICATE KEY UPDATE item_key=item_key",
                itemKey, initialStock, now);
    }

    /**
     * Atomic buy: stock-=amount + tx-log, both in one transaction.
     * Returns true if stock decrement succeeded.
     */
    public boolean applyBuyWithLogTx(String itemKey, long amount, BigDecimal newBuyPrice,
                                     UUID player, String playerName, BigDecimal total, long now) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(STOCK_TABLE) + " SET stock=stock-?, last_buy_price=?, updated_at=? " +
                            " WHERE item_key=? AND stock>=?",
                    amount, newBuyPrice, now, itemKey, amount);
            if (updated != 1) {
                return false;
            }
            tx.update(
                    "INSERT INTO " + tx.t(TX_TABLE) +
                            " (player_uuid, player_name, item_key, side, amount, unit_price, total_price, created_at)" +
                            " VALUES (?, ?, ?, 'BUY', ?, ?, ?, ?)",
                    player.toString(), playerName, itemKey, (int) amount, newBuyPrice, total, now);
            return true;
        });
    }

    /**
     * Atomic sell: stock+=amount + tx-log.
     */
    public boolean applySellWithLogTx(String itemKey, long amount, BigDecimal newSellPrice,
                                      UUID player, String playerName, BigDecimal total, long now) {
        return db.tx(tx -> {
            int updated = tx.update(
                    "UPDATE " + tx.t(STOCK_TABLE) + " SET stock=stock+?, last_sell_price=?, updated_at=? " +
                            " WHERE item_key=?",
                    amount, newSellPrice, now, itemKey);
            if (updated != 1) {
                return false;
            }
            tx.update(
                    "INSERT INTO " + tx.t(TX_TABLE) +
                            " (player_uuid, player_name, item_key, side, amount, unit_price, total_price, created_at)" +
                            " VALUES (?, ?, ?, 'SELL', ?, ?, ?, ?)",
                    player.toString(), playerName, itemKey, (int) amount, newSellPrice, total, now);
            return true;
        });
    }

    /** Rollback helpers used as compensation when economy fails after the stock change. */
    public void compensateBuyRollback(String itemKey, long amount, long now) {
        db.update(
                "UPDATE " + t() + " SET stock=stock+?, updated_at=? WHERE item_key=?",
                amount, now, itemKey);
    }

    public void compensateSellRollback(String itemKey, long amount, long now) {
        db.update(
                "UPDATE " + t() + " SET stock=stock-?, updated_at=? WHERE item_key=? AND stock>=?",
                amount, now, itemKey, amount);
    }

    private static BazaarStock map(ResultSet rs) throws SQLException {
        return new BazaarStock(
                rs.getString("item_key"),
                rs.getLong("stock"),
                rs.getBigDecimal("last_buy_price"),
                rs.getBigDecimal("last_sell_price"),
                rs.getLong("updated_at")
        );
    }
}

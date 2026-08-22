package hexnpc.shop;

import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Test double dla {@link EconomyBridge} rejestrujący wywołania i kwoty oraz
 * pozwalający sterować wynikiem (natychmiastowy lub sterowany future).
 */
public final class RecordingEconomyBridge extends EconomyBridge {

    public TxResult nextWithdraw = TxResult.ok(BigDecimal.ZERO);
    public TxResult nextDeposit = TxResult.ok(BigDecimal.ZERO);
    public CompletableFuture<TxResult> nextWithdrawFuture;
    public CompletableFuture<TxResult> nextDepositFuture;

    public int withdrawCalls = 0;
    public int depositCalls = 0;
    public int hasCalls = 0;
    public BigDecimal lastWithdraw;
    public BigDecimal lastDeposit;

    public RecordingEconomyBridge() {
        super(Logger.getLogger("recording"));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String currencyName() {
        return "$";
    }

    @Override
    public String format(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    @Override
    public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName, BigDecimal amount, String reason) {
        withdrawCalls++;
        lastWithdraw = amount;
        if (nextWithdrawFuture != null) {
            CompletableFuture<TxResult> f = nextWithdrawFuture;
            nextWithdrawFuture = null;
            return f;
        }
        return CompletableFuture.completedFuture(nextWithdraw);
    }

    @Override
    public CompletableFuture<TxResult> deposit(UUID uuid, String playerName, BigDecimal amount, String reason) {
        depositCalls++;
        lastDeposit = amount;
        if (nextDepositFuture != null) {
            CompletableFuture<TxResult> f = nextDepositFuture;
            nextDepositFuture = null;
            return f;
        }
        return CompletableFuture.completedFuture(nextDeposit);
    }
}

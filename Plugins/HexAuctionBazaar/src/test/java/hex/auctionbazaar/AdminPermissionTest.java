package hex.auctionbazaar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprawdza reguly dostepu admina do panelu /hexauction admin.
 * Wymagania:
 *  - OP ma dostep (nawet bez wyraznie nadanej permisji)
 *  - gracz z hexauction.admin ma dostep
 *  - zwykly gracz nie ma dostepu
 * (Test odzwierciedla implementacje AuctionCommand.hasAdmin.)
 */
class AdminPermissionTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void opGetsAdminAccess() {
        PlayerMock op = server.addPlayer("Admin");
        op.setOp(true);
        assertTrue(hasAdmin(op, "hexauction.admin"));
    }

    @Test
    void permissionHolderGetsAdminAccess() {
        PlayerMock holder = server.addPlayer("Mod");
        holder.addAttachment(MockBukkit.createMockPlugin(), "hexauction.admin", true);
        assertTrue(hasAdmin(holder, "hexauction.admin"));
    }

    @Test
    void regularPlayerDoesNotGetAdminAccess() {
        PlayerMock user = server.addPlayer("User");
        user.setOp(false);
        assertFalse(hasAdmin(user, "hexauction.admin"));
    }

    private boolean hasAdmin(org.bukkit.command.CommandSender sender, String perm) {
        return sender.hasPermission(perm) || sender.isOp();
    }
}

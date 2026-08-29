package hex.limbo.auth;

import hex.limbo.testsupport.AuthFlowFixture;
import hex.limbo.testsupport.FakeConnection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate {@code CommandListener} and {@code ChatListener} consult before letting a player act.
 *
 * <p>It has to be fail-closed. The dangerous case is a reconnect that overtakes its own
 * {@code DisconnectEvent}: connection B takes the UUID over, and A - which never authenticated - is
 * still delivering events. Resolving A by UUID finds B, and any "unknown, so allow" fallback then
 * hands A the privileges B earned. A must be blocked, and must never be judged against B's state.
 */
class ConnectionGateTest {

    private final AuthFlowFixture fixture = new AuthFlowFixture();
    private final ConnectionRegistry registry = fixture.registry;

    /** Exactly what the two listeners call. */
    private boolean allowed(FakeConnection player) {
        return registry.isAuthenticatedConnection(player.uuid(), player);
    }

    @Test
    void anUnauthenticatedCurrentConnectionIsBlocked() {
        FakeConnection player = FakeConnection.of("Adam");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);

        assertFalse(handle.isAuthenticated(), "precondition: the player still has to /login");
        assertFalse(allowed(player), "an unauthenticated player must be blocked");
    }

    @Test
    void anAuthenticatedCurrentConnectionIsAllowed() {
        fixture.seedAccount("Bea", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Bea".getBytes()), "Bea");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.flow.login(handle, "verylongpw");

        assertTrue(handle.isAuthenticated(), "precondition: the login committed");
        assertTrue(allowed(player), "a normally authenticated player must keep working");
    }

    /** The security hole: A is unauthenticated, B takes the UUID over, A must stay blocked. */
    @Test
    void anUnauthenticatedOldSocketIsBlockedAfterAReconnectTookTheUuidOver() {
        fixture.seedAccount("Cleo", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Cleo".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Cleo");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        assertFalse(oldHandle.isAuthenticated());

        // Connection B arrives before A's DisconnectEvent is delivered, and authenticates.
        FakeConnection second = new FakeConnection(uuid, "Cleo");
        ConnectionHandle newHandle = fixture.connect(second);
        fixture.joinCracked(newHandle);
        fixture.flow.login(newHandle, "verylongpw");
        assertTrue(newHandle.isAuthenticated(), "precondition: B is authenticated");

        assertFalse(allowed(first),
                "the superseded, unauthenticated socket must not inherit the new connection's rights");
        assertTrue(allowed(second), "B is judged by its own handle and keeps working");
    }

    /** Same shape, but B has not authenticated either: A still must not be let through. */
    @Test
    void anOldSocketIsBlockedEvenWhenTheReplacementIsAlsoUnauthenticated() {
        UUID uuid = UUID.nameUUIDFromBytes("u:Dana".getBytes());
        FakeConnection first = new FakeConnection(uuid, "Dana");
        fixture.joinCracked(fixture.connect(first));
        FakeConnection second = new FakeConnection(uuid, "Dana");
        fixture.joinCracked(fixture.connect(second));

        assertFalse(allowed(first));
        assertFalse(allowed(second));
    }

    /** An authenticated socket that has since been superseded must also lose its rights. */
    @Test
    void anAuthenticatedOldSocketLosesItsRightsOnceSuperseded() {
        fixture.seedAccount("Emil", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Emil".getBytes());

        FakeConnection first = new FakeConnection(uuid, "Emil");
        ConnectionHandle oldHandle = fixture.connect(first);
        fixture.joinCracked(oldHandle);
        fixture.flow.login(oldHandle, "verylongpw");
        assertTrue(allowed(first), "precondition: A was allowed while it was current");

        FakeConnection second = new FakeConnection(uuid, "Emil");
        fixture.joinCracked(fixture.connect(second));

        assertFalse(allowed(first), "a superseded socket must stop being allowed");
    }

    @Test
    void aPlayerWithNoRegisteredHandleIsFailedClosed() {
        FakeConnection stranger = FakeConnection.of("Fiona");

        assertFalse(allowed(stranger),
                "a player HexLimbo never registered must not be treated as authenticated");
    }

    @Test
    void aDisconnectedConnectionIsFailedClosed() {
        fixture.seedAccount("Gustaw", "verylongpw");
        FakeConnection player = new FakeConnection(UUID.nameUUIDFromBytes("u:Gustaw".getBytes()), "Gustaw");
        ConnectionHandle handle = fixture.connect(player);
        fixture.joinCracked(handle);
        fixture.flow.login(handle, "verylongpw");
        assertTrue(allowed(player));

        fixture.disconnect(player);

        assertFalse(allowed(player), "a connection that ended must stop being allowed");
    }

    /** A different Player object with the same UUID is a different connection, full stop. */
    @Test
    void aForeignPlayerObjectWithTheSameUuidIsFailedClosed() {
        fixture.seedAccount("Hania", "verylongpw");
        UUID uuid = UUID.nameUUIDFromBytes("u:Hania".getBytes());
        FakeConnection real = new FakeConnection(uuid, "Hania");
        ConnectionHandle handle = fixture.connect(real);
        fixture.joinCracked(handle);
        fixture.flow.login(handle, "verylongpw");

        FakeConnection impostor = new FakeConnection(uuid, "Hania");

        assertTrue(allowed(real));
        assertFalse(allowed(impostor), "identity, not UUID, decides");
    }

    /**
     * Admin bypass is a permission check the listeners do <em>before</em> this gate, so it is
     * unaffected by the fail-closed behaviour. This pins that the bypass join still produces an
     * authenticated connection, i.e. such a player is allowed on both paths.
     */
    @Test
    void adminBypassStillProducesAnAuthenticatedConnection() {
        FakeConnection staff = FakeConnection.of("Iwona");
        ConnectionHandle handle = fixture.connect(staff);

        fixture.flow.resolveJoin(handle, new AuthFlow.JoinRequest(false, true, "ip-hash"));

        assertTrue(handle.isAuthenticated(), "an admin-bypass join authenticates immediately");
        assertTrue(allowed(staff), "and the gate lets them through on their own handle");
    }
}

package hex.limbo.testsupport;

import hex.limbo.auth.ConnectionHandle;
import hex.limbo.auth.ConnectionRegistry;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for a Velocity {@code Player} in tests: it is both the connection identity that
 * {@link ConnectionHandle#isFor(Object)} compares against and the Adventure {@link Audience} the
 * prompt renders to, exactly like a real {@code Player}.
 *
 * <p>Two {@code FakeConnection}s created for the same UUID are different objects, which is what
 * lets a test model a reconnect and check that an old socket's work cannot touch the new one.
 */
public final class FakeConnection implements Audience {

    private final UUID uuid;
    private final String username;

    public final List<Component> messages = new CopyOnWriteArrayList<>();
    public final List<BossBar> shownBars = new CopyOnWriteArrayList<>();
    public final List<BossBar> hiddenBars = new CopyOnWriteArrayList<>();
    public final List<Title> titles = new CopyOnWriteArrayList<>();

    public FakeConnection(UUID uuid, String username) {
        this.uuid = uuid;
        this.username = username;
    }

    public static FakeConnection of(String username) {
        return new FakeConnection(UUID.nameUUIDFromBytes(("u:" + username).getBytes()), username);
    }

    /** Opens a fresh connection for this socket, the way {@code LoginEvent} does. */
    public ConnectionHandle connect(ConnectionRegistry registry) {
        return registry.begin(uuid, username, this, this).handle();
    }

    /** Opens a connection and hands back the registration, including any superseded handle. */
    public ConnectionRegistry.Registration connectReturningRegistration(ConnectionRegistry registry) {
        return registry.begin(uuid, username, this, this);
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    /** Plain text of the last chat line, for readable assertions. */
    public String lastMessage() {
        return messages.isEmpty() ? null : plain(messages.get(messages.size() - 1));
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public List<Title> titles() {
        return Collections.unmodifiableList(titles);
    }

    @Override
    public void sendMessage(Component message) {
        messages.add(message);
    }

    @Override
    public void showBossBar(BossBar bar) {
        shownBars.add(bar);
    }

    @Override
    public void hideBossBar(BossBar bar) {
        hiddenBars.add(bar);
    }

    @Override
    public void showTitle(Title title) {
        titles.add(title);
    }

    @Override
    public String toString() {
        return "FakeConnection[" + username + "@" + Integer.toHexString(System.identityHashCode(this)) + "]";
    }
}

package hex.limbo.auth;

import net.kyori.adventure.audience.Audience;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The one place that decides which physical connection currently owns a UUID, and the one place
 * that serialises everything those connections do to shared, persistent state.
 *
 * <p>Everything HexLimbo tracks per player - auth state, login timeout, limbo prompt, pending lobby
 * greeting - is scoped to a {@link ConnectionHandle} obtained here, rather than to a bare UUID.
 * That gives the whole plugin a single connection lifecycle with two rules:
 *
 * <ol>
 *     <li><b>Nothing mutates state without proving it is still current.</b> Asynchronous work
 *     (password verification, Mojang lookups, DB reads) captures its handle up front and re-checks
 *     with {@link #isCurrent} before it commits. A worker whose player disconnected - or whose UUID
 *     has since been taken over by a fresh connection - is rejected instead of writing through.</li>
 *     <li><b>Nothing is removed by UUID alone.</b> {@link #endIfCurrent} only tears down the
 *     connection whose {@code Player} instance matches, so a {@code DisconnectEvent} that arrives
 *     late - after the same account has already reconnected - cannot wipe the live connection.</li>
 * </ol>
 *
 * <p>Registration happens synchronously at the very start of {@code LoginEvent}, before any
 * asynchronous work is dispatched. Nothing may create a connection later on: a handle either
 * already exists for a player or that player is not ours to track, which is what keeps a late
 * event from resurrecting state after a disconnect.
 *
 * <h2>The per-UUID commit slot</h2>
 * A currency check alone cannot order two operations against each other. Two connections for the
 * same account - or a {@code /login} and the {@code /logout} that follows it - can each be current
 * at the moment they look, and still interleave their reads and writes afterwards. Every operation
 * that reads the account row in order to decide something, writes to the database, or changes an
 * {@link AuthState} therefore runs inside <b>the UUID's commit slot</b>, an exclusive section
 * handed out by {@link #enterCommitOrder} and {@link #beginCommit}:
 *
 * <ul>
 *     <li>At most one commit is ever active per UUID, <em>including</em> across a reconnect: the
 *     slot is keyed by UUID, not by handle, so a fresh connection that took the account over still
 *     queues behind its predecessor instead of racing it.</li>
 *     <li>Because the section spans the decision reads as well as the writes, a connection can
 *     never decide on a repository state that an earlier commit has already superseded. It waits,
 *     and then reads the finished picture.</li>
 * </ul>
 *
 * <p>The slot deliberately does <b>not</b> cover {@link #begin} or {@link #endIfCurrent}. A new
 * socket is registered synchronously on Velocity's {@code LoginEvent} thread and a disconnect must
 * be able to tear a connection down immediately; neither may ever park behind a slow database call.
 * Ordering is provided for the asynchronous flows that follow, not for the socket lifecycle.
 *
 * <h2>The player-facing half</h2>
 * Committing in order is only half the guarantee: the message, the routing and the kick a flow
 * decided on are applied <em>after</em> the flow returned, and a second operation can complete in
 * that gap. {@link #applyOrdered} closes it. Every operation stamps its result with an
 * {@link OperationStamp}, and the effects are replayed inside the same per-UUID slot, under the
 * condition that the stamped operation is still the connection's latest. An overtaken result is
 * dropped in full, so an old {@code /login} can never announce success or route to the target after
 * a later {@code /logout} has already sent the player back to the limbo.
 *
 * <p>The maps hold exactly the connections that are currently tracked and exactly the UUIDs with a
 * commit in flight or queued, so both are bounded by the number of online players and return to
 * zero once everybody has left.
 */
public final class ConnectionRegistry {

    /**
     * The freshly opened connection, plus the one it displaced.
     *
     * @param handle     the new, current connection
     * @param superseded a previous connection for the same UUID whose {@code DisconnectEvent} had
     *                   not arrived yet, or {@code null}. The caller must tear its resources down,
     *                   because the late disconnect will no longer match it.
     */
    public record Registration(ConnectionHandle handle, ConnectionHandle superseded) {}

    /** What {@link #applyOrdered} did with a set of player-facing effects. */
    public enum ApplyOutcome {
        /** The effects ran. */
        APPLIED,
        /** A later operation on the same connection had already taken effect. */
        OVERTAKEN,
        /** The connection has ended, been superseded, or is not the socket that asked. */
        STALE_CONNECTION,
        /** The result carried no ordering stamp, so it has no place in the order. */
        UNSTAMPED,
        /** The result asks for nothing at all. */
        NO_EFFECT;

        /** Whether the effects were actually performed. */
        public boolean applied() {
            return this == APPLIED;
        }
    }

    /**
     * Identifies one ordered operation of one connection: the ticket a flow result needs in order
     * to be applied to the player.
     *
     * <p>A stamp names both halves of the question. {@code handle} pins the physical connection, so
     * a result belonging to a socket that has since disconnected or been superseded is refused
     * outright. {@code operation} pins <em>which</em> of that connection's operations produced the
     * result, so two results of the same, still-live connection can be ordered against each other -
     * which is exactly what a {@code /login} racing a {@code /logout} needs and what a plain "is
     * this connection still current" check cannot express.
     */
    public record OperationStamp(ConnectionHandle handle, long operation) {}

    /**
     * One UUID's exclusive commit slot.
     *
     * <p>{@code users} counts the threads that hold or are queued for {@link #lock}. It is only
     * ever mutated inside a {@code commitSlots} compute block, in the same atomic step in which a
     * thread obtains or lets go of its reference to the slot. That is what makes the entry safe to
     * remove: it can only reach zero when nobody holds a reference any more, so two threads can
     * never end up locking two different slot objects for the same UUID.
     */
    private static final class CommitSlot {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private final ConcurrentHashMap<UUID, ConnectionHandle> connections = new ConcurrentHashMap<>();
    /** Commit slots for UUIDs with a commit in flight or queued behind one. */
    private final ConcurrentHashMap<UUID, CommitSlot> commitSlots = new ConcurrentHashMap<>();
    /** The handle whose commit currently holds each UUID's slot. At most one entry per UUID. */
    private final ConcurrentHashMap<UUID, ConnectionHandle> activeCommits = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private volatile Consumer<ConnectionHandle> commitQueuedObserver = handle -> { };
    /**
     * The failure-observation scope of the calling thread, or {@code null} when none is open.
     *
     * <p>Deliberately holds primitives only. An earlier version parked a whole
     * {@link OperationStamp} here on <em>every</em> section entry, which pins the
     * {@link ConnectionHandle} and through it the {@code Player} and its {@code Audience} - on a
     * pooled executor or a Velocity event thread that means the last connection a thread touched is
     * never released, and on externally managed threads it can pin the plugin classloader too. A
     * scope is now opened only by {@link #observeOperations()}, closed in a {@code finally}, and
     * records nothing but two {@code long}s.
     */
    private final ThreadLocal<OperationObservation> observations = new ThreadLocal<>();

    /**
     * What one failure-observation scope saw: the connection token and operation id of the last
     * ordered section its thread entered. Two {@code long}s and nothing else - no handle, no player,
     * no audience.
     */
    private static final class OperationObservation {
        private static final long NONE = Long.MIN_VALUE;
        private final OperationObservation previous;
        private long handleToken = NONE;
        private long operation = NONE;

        OperationObservation(OperationObservation previous) {
            this.previous = previous;
        }
    }

    /**
     * An open window in which this thread's ordered-section entries are recorded, so a flow that
     * throws can still name the operation it really ran as.
     *
     * <p>Must be closed on every path; {@code try}-with-resources is the only supported use.
     */
    public final class FailureObservation implements AutoCloseable {

        private final OperationObservation scope;
        private boolean closed;

        private FailureObservation(OperationObservation scope) {
            this.scope = scope;
        }

        /**
         * The operation this thread entered for {@code handle} inside this scope, if any.
         *
         * <p>Empty when the thread entered no section, or when the one it entered belonged to a
         * different connection. There is deliberately no fallback: an operation id that was never
         * handed out is not a stamp, and guessing one is what this replaces.
         */
        public Optional<OperationStamp> operationFor(ConnectionHandle handle) {
            if (handle == null || scope.handleToken != handle.token()
                    || scope.operation == OperationObservation.NONE) {
                return Optional.empty();
            }
            return Optional.of(new OperationStamp(handle, scope.operation));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (scope.previous == null) {
                observations.remove();
            } else {
                observations.set(scope.previous);
            }
        }
    }

    /**
     * Opens a failure-observation scope for the calling thread. Nothing outside such a scope is
     * recorded, so no thread ever holds on to a connection it merely happened to serve.
     */
    public FailureObservation observeOperations() {
        OperationObservation scope = new OperationObservation(observations.get());
        observations.set(scope);
        return new FailureObservation(scope);
    }

    /** Test/diagnostic hook: whether this thread currently has an observation scope open. */
    public boolean hasObservationScope() {
        return observations.get() != null;
    }

    /**
     * Opens a connection for the given player and makes it the current one for that UUID. Call this
     * synchronously, before dispatching any asynchronous login work.
     *
     * <p>Never blocks, and in particular never waits for a commit that is still in flight for the
     * same UUID: the {@code LoginEvent}/Netty thread must not be parked behind a database call. The
     * asynchronous flow that follows does the waiting, in {@link #enterCommitOrder}.
     *
     * @param connection the concrete {@code Player} instance; kept for identity comparison only
     */
    public Registration begin(UUID uuid, String username, Object connection, Audience audience) {
        ConnectionHandle handle = new ConnectionHandle(tokens.incrementAndGet(), uuid, username, connection, audience);
        ConnectionHandle previous = connections.put(uuid, handle);
        return new Registration(handle, previous);
    }

    /** The connection that currently owns the UUID, if any. */
    public Optional<ConnectionHandle> current(UUID uuid) {
        return Optional.ofNullable(connections.get(uuid));
    }

    /**
     * The current connection for the UUID, but only when it belongs to {@code connection}. This is
     * how event handlers turn a {@code Player} they were handed into a handle they may act on.
     */
    public Optional<ConnectionHandle> currentFor(UUID uuid, Object connection) {
        ConnectionHandle handle = connections.get(uuid);
        return handle != null && handle.isFor(connection) ? Optional.of(handle) : Optional.empty();
    }

    /**
     * The authentication gate for an event that carries a concrete {@code Player}, and the only
     * form event handlers may use. <b>Fail-closed:</b> it answers {@code true} only when this exact
     * connection is the one currently registered <em>and</em> that connection is authenticated.
     *
     * <p>Every other case answers {@code false} and the caller must block the action:
     * <ul>
     *     <li><b>No handle at all</b> - the login pipeline never registered this player (a denied
     *     login, a plugin reload mid-session). Nothing proves they authenticated, so they do not
     *     get to act.</li>
     *     <li><b>A superseded socket</b> - the same account reconnected and connection B took the
     *     UUID over before A's {@code DisconnectEvent} was delivered. A's events must be blocked,
     *     and critically they must <em>not</em> be judged against B's auth state: answering
     *     "unknown, so allow" here would let a dying, unauthenticated socket run any command or
     *     say anything the moment its replacement logged in.</li>
     * </ul>
     *
     * <p>Admin bypass is a separate permission check the callers do first; it is unaffected.
     */
    public boolean isAuthenticatedConnection(UUID uuid, Object connection) {
        return currentFor(uuid, connection).map(ConnectionHandle::isAuthenticated).orElse(false);
    }

    /** True while {@code handle} is still the connection that owns its UUID. */
    public boolean isCurrent(ConnectionHandle handle) {
        return handle != null && connections.get(handle.uuid()) == handle;
    }

    /**
     * Whether the login timeout armed by {@code handle} may still kick {@code onlinePlayer}.
     *
     * <p>Three things all have to hold, and a stale timeout fails at least one of them: the handle
     * must still be the current connection, it must still be unauthenticated, and the player who is
     * online under that UUID must be the very socket the timeout was armed for. Without the last
     * check a timeout from a dead connection would kick the reconnect that took the UUID over.
     */
    public boolean shouldTimeOut(ConnectionHandle handle, Object onlinePlayer) {
        return isCurrent(handle) && !handle.isAuthenticated() && handle.isFor(onlinePlayer);
    }

    /**
     * An exclusive, ordered section for one UUID: the right to run one authentication operation
     * while no other operation for the same account can be running.
     *
     * <h2>Linearization point</h2>
     * <b>The linearization point of an operation is the moment it acquires the UUID's commit slot -
     * that is, the moment {@link ConnectionRegistry#enterCommitOrder} or
     * {@link ConnectionRegistry#beginCommit} returns.</b> The slot is held until the lease is
     * closed, so the operation's decision reads, its persistent writes, its auth-state change and
     * its bookkeeping (session row, audit entry, queued greeting) all fall inside a single section.
     * That is what the order is defined over:
     *
     * <ul>
     *     <li>Two operations on the same UUID never interleave, whether they belong to the same
     *     connection or to a reconnect that took the account over. The one that acquires the slot
     *     second observes everything the first did - including rows it created - because it does
     *     its own reads after the hand-off.</li>
     *     <li>An operation that acquires the slot later therefore <em>wins</em>. A {@code /logout}
     *     issued while a {@code /login} is still writing is applied after the login completes, and
     *     the player ends up logged out with the login's session invalidated - not the other way
     *     round.</li>
     *     <li>Two different UUIDs are never ordered against each other and commit in parallel.</li>
     * </ul>
     *
     * <h2>What the slot does not order</h2>
     * Disconnects and reconnects are deliberately outside it, so they stay immediate. A supersede
     * or disconnect can therefore still land <em>while</em> a lease is held, and the guarantee for
     * that case is unchanged: the operation counts as having happened. Its persistent write and its
     * auth-state change both go through - the state simply hangs off a handle nobody can reach any
     * more - and only what is addressed at the player (greeting, chat confirmation, routing) is
     * suppressed, by checking {@link #isCurrent()} separately.
     *
     * <p>A lease is used by exactly one thread and must be released exactly once. Every caller uses
     * try-with-resources, which is what guarantees the slot is freed on the exception paths too;
     * {@link #commit(Runnable)} additionally releases it if the state callback itself throws, so a
     * misbehaving callback can never strand a UUID.
     */
    public static final class CommitLease implements AutoCloseable {

        private final ConnectionRegistry registry;
        private final ConnectionHandle handle;
        private final CommitSlot slot;
        private final long operation;
        private boolean released;

        private CommitLease(ConnectionRegistry registry, ConnectionHandle handle, CommitSlot slot, long operation) {
            this.registry = registry;
            this.handle = handle;
            this.slot = slot;
            this.operation = operation;
        }

        /** The connection this lease was taken for. */
        public ConnectionHandle handle() {
            return handle;
        }

        /**
         * The ticket this operation's player-facing result must carry. Pass it to
         * {@link AuthFlow.Result#at(OperationStamp)} so {@link ConnectionRegistry#applyOrdered} can
         * tell later whether the result is still the connection's latest word.
         */
        public OperationStamp stamp() {
            return new OperationStamp(handle, operation);
        }

        /**
         * Whether the connection this section was entered for still owns its UUID.
         *
         * <p>This is the <em>only</em> thing {@link ConnectionRegistry#beginCommit} adds over
         * {@link ConnectionRegistry#enterCommitOrder}, and the distinction matters: "another commit
         * for this UUID is in progress" is never reported as a refusal, because the section waits
         * for it. A lease that reports {@code false} here means one specific thing - this handle is
         * stale - and nothing else.
         */
        public boolean isCurrent() {
            return registry.isCurrent(handle);
        }

        /**
         * Applies this operation's auth-state change inside the section.
         *
         * <p>The lease is <em>not</em> released here: the rest of the operation's bookkeeping -
         * session row, audit entry, prompt teardown, queued greeting - belongs to the same ordered
         * section and must not be overtaken by a {@code /logout} that was issued later. The caller's
         * try-with-resources releases it. If {@code stateChange} throws, the slot is released before
         * the exception propagates rather than left stranded.
         */
        public void commit(Runnable stateChange) {
            if (released) {
                throw new IllegalStateException("commit lease already released for " + handle);
            }
            try {
                stateChange.run();
            } catch (RuntimeException | Error stateChangeFailure) {
                close();
                throw stateChangeFailure;
            }
        }

        /** Ends the section without applying anything. Use when the persistent write failed. */
        public void abort() {
            close();
        }

        /** Ends the section. Idempotent, so it is safe from both {@code finally} and {@code abort}. */
        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            registry.leaveCommitOrder(handle.uuid(), slot);
        }
    }

    /**
     * Enters the UUID's ordered section, waiting for any commit already in flight for the same
     * UUID, and <b>without</b> asserting that {@code handle} is still current.
     *
     * <p>This is the form the join pipeline needs. A premium join has to create or migrate the
     * account row before anybody can decide whether the join is allowed, and that provisioning is
     * deliberately kept even when the connection turns out to be gone (see {@code AuthFlow}). It
     * still has to be ordered against every other commit for the account, hence the section; it
     * just must not be skipped on the strength of a stale handle. Callers check
     * {@link CommitLease#isCurrent()} - or, as the join does, commit through
     * {@link #attachAuthState} - where currency actually matters.
     *
     * <p>Everything that follows the acquisition is ordered against other commits for this UUID;
     * see {@link CommitLease} for the exact guarantees. The wait happens outside the map's per-key
     * lock, so a slow predecessor never stalls unrelated UUIDs.
     *
     * @throws IllegalStateException if this thread already holds a lease for the same UUID; nesting
     *                               commits would silently break the "one commit per UUID" invariant
     */
    public CommitLease enterCommitOrder(ConnectionHandle handle) {
        Objects.requireNonNull(handle, "handle");
        CommitSlot slot = acquireSlot(handle);
        // Claiming the operation id under the slot is what makes the ids reflect the commit order.
        long operation = handle.beginOperation();
        activeCommits.put(handle.uuid(), handle);
        // Recorded only while somebody is watching for a failure, and only as two longs.
        OperationObservation scope = observations.get();
        if (scope != null) {
            scope.handleToken = handle.token();
            scope.operation = operation;
        }
        return new CommitLease(this, handle, slot, operation);
    }

    /**
     * Enters the UUID's ordered section, waits out any commit in flight, and runs {@code effects}
     * only if {@code stamp} is still the last word on that connection.
     *
     * <p>This is where a flow result becomes visible to the player, and it is deliberately the same
     * section the commits use. Three things must hold, all checked <em>inside</em> the section so
     * nothing can change between the check and the effects:
     *
     * <ol>
     *     <li>the stamped handle still owns its UUID - a disconnected or superseded socket gets
     *     nothing at all;</li>
     *     <li>the caller really is that socket, compared by identity, so a result can never be
     *     delivered to the {@code Player} that replaced it;</li>
     *     <li>the stamped operation is still the connection's latest. This is the part a currency
     *     check cannot do: a {@code /login} and the {@code /logout} that follows it concern the same
     *     live connection, and only the operation id tells them apart.</li>
     * </ol>
     *
     * <p>Because the section is the commit section, an operation that commits after this one cannot
     * be committing while these effects run, and this one cannot run its effects after that later
     * operation has committed - it would see a newer operation id and drop out.
     *
     * <p>{@code effects} must be short and non-blocking; in production it is a chat line, a
     * connection request or a kick. The slot is released in a {@code finally}, so an effect that
     * throws cannot strand the UUID.
     *
     * @return what happened, so a caller that has to report an outcome - staff commands above all -
     *         can tell "done" from "overtaken" from "the player is gone"
     */
    public ApplyOutcome applyOrdered(OperationStamp stamp, Object connection, Runnable effects) {
        if (stamp == null || stamp.handle() == null) {
            return ApplyOutcome.UNSTAMPED; // fail closed: no stamp, no place in the order
        }
        ConnectionHandle handle = stamp.handle();
        CommitSlot slot = acquireSlot(handle);
        try {
            if (!isCurrent(handle) || !handle.isFor(connection)) {
                return ApplyOutcome.STALE_CONNECTION;
            }
            if (handle.currentOperation() != stamp.operation()) {
                return ApplyOutcome.OVERTAKEN;
            }
            effects.run();
            return ApplyOutcome.APPLIED;
        } finally {
            releaseSlot(handle.uuid(), slot);
        }
    }

    /**
     * Takes exclusive hold of the UUID's slot, refcounting the entry so it can be dropped again
     * once nobody holds or wants it.
     *
     * @throws IllegalStateException if this thread already holds the same slot; nesting would
     *                               silently break the "one operation per UUID at a time" invariant
     */
    /**
     * Runs {@code action} inside the UUID's ordered section while {@code handle} is still the
     * connection that owns it and is still this socket.
     *
     * <p>Like {@link #applyOrdered} but with no operation stamp, for work no auth operation issued -
     * the corrective route the arrival handler decides on, and the fail-closed teardown that follows
     * it. Ordering still holds, so the action cannot interleave with an auth operation.
     */
    public ApplyOutcome runInOrder(ConnectionHandle handle, Object connection, Runnable action) {
        if (handle == null) {
            return ApplyOutcome.STALE_CONNECTION;
        }
        CommitSlot slot = acquireSlot(handle);
        try {
            if (!isCurrent(handle) || !handle.isFor(connection)) {
                return ApplyOutcome.STALE_CONNECTION;
            }
            action.run();
            return ApplyOutcome.APPLIED;
        } finally {
            releaseSlot(handle.uuid(), slot);
        }
    }

    /**
     * Whether this thread is already inside the UUID's ordered section.
     *
     * <p>Callers that may or may not be running under it - the routing terminal path, reached either
     * from a scheduler thread or inline from a transport that answered synchronously - use this to
     * avoid re-entering, which {@link #enterCommitOrder} rejects outright.
     */
    public boolean holdsCommitOrder(UUID uuid) {
        CommitSlot slot = commitSlots.get(uuid);
        return slot != null && slot.lock.isHeldByCurrentThread();
    }

    private CommitSlot acquireSlot(ConnectionHandle handle) {
        UUID uuid = handle.uuid();
        CommitSlot slot = commitSlots.compute(uuid, (id, existing) -> {
            CommitSlot claimed = existing != null ? existing : new CommitSlot();
            claimed.users++;
            return claimed;
        });
        // Park outside the compute block on purpose: waiting for a slow database call while holding
        // the map's per-key lock would stall every other UUID in the same hash bin.
        if (!slot.lock.tryLock()) {
            commitQueuedObserver.accept(handle);
            slot.lock.lock();
        }
        if (slot.lock.getHoldCount() > 1) {
            slot.lock.unlock();
            dropSlotUser(uuid);
            throw new IllegalStateException("nested commit for " + uuid
                    + ": an operation must not start a second commit while it already holds one");
        }
        return slot;
    }

    private void releaseSlot(UUID uuid, CommitSlot slot) {
        slot.lock.unlock();
        dropSlotUser(uuid);
    }

    /**
     * Enters the UUID's ordered section and refuses it when {@code handle} turns out to be stale.
     *
     * <p>The currency check happens <em>after</em> the wait, never before it: a connection must not
     * be judged - nor decide anything on repository data - while an earlier commit for the same
     * UUID is still unfinished. An empty result therefore has exactly one meaning, "this handle no
     * longer owns its UUID"; a busy UUID is waited for, not reported.
     *
     * <p>Callers must hold the lease for the whole operation - decision reads included - and close
     * it with try-with-resources.
     *
     * @return the lease, or empty when the connection has ended or been superseded
     */
    public Optional<CommitLease> beginCommit(ConnectionHandle handle) {
        if (handle == null) {
            return Optional.empty();
        }
        CommitLease lease = enterCommitOrder(handle);
        if (!lease.isCurrent()) {
            lease.close();
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    private void leaveCommitOrder(UUID uuid, CommitSlot slot) {
        activeCommits.remove(uuid);
        releaseSlot(uuid, slot);
    }

    /** Drops this thread's claim on the slot, removing the entry once nobody holds or wants it. */
    private void dropSlotUser(UUID uuid) {
        commitSlots.computeIfPresent(uuid, (id, existing) -> {
            existing.users--;
            return existing.users <= 0 ? null : existing;
        });
    }

    /**
     * Diagnostic seam, invoked on the calling thread the instant before it parks behind another
     * commit for the same UUID. Production leaves it unset; tests install a latch so a hand-off can
     * be awaited rather than slept on.
     */
    public void setCommitQueuedObserver(Consumer<ConnectionHandle> observer) {
        this.commitQueuedObserver = observer == null ? handle -> { } : observer;
    }

    /** Test/diagnostic hook: commits currently in flight. Must return to zero. */
    public int commitsInFlight() {
        return activeCommits.size();
    }

    /** Test/diagnostic hook: whether a commit currently holds this UUID's slot. */
    public boolean isCommitting(UUID uuid) {
        return activeCommits.containsKey(uuid);
    }

    /**
     * Test/diagnostic hook: UUIDs with a commit slot allocated, i.e. one in flight or one queued.
     * Must return to zero, which is what keeps the structure bounded across many players.
     */
    public int commitSlotsTracked() {
        return commitSlots.size();
    }

    /**
     * Runs {@code action} if - and while - {@code handle} is still current, atomically with respect
     * to {@link #endIfCurrent} and {@link #begin} for the same UUID.
     *
     * <p>This is the right tool for a state change that has <em>no</em> persistent side effect and
     * needs no ordering against other operations, such as arming the login timeout. Anything that
     * reads the account row to decide something, writes to the database, or has to be ordered
     * against a concurrent {@code /login} or {@code /logout} must go through
     * {@link #beginCommit(ConnectionHandle)} instead, because a check-then-act pair cannot be made
     * atomic by re-checking.
     *
     * <p>Keep {@code action} short; it runs under the map's per-key lock.
     *
     * @return whether the action ran
     */
    public boolean commitIfCurrent(ConnectionHandle handle, Runnable action) {
        if (handle == null) {
            return false;
        }
        boolean[] committed = new boolean[1];
        connections.computeIfPresent(handle.uuid(), (id, current) -> {
            if (current == handle) {
                action.run();
                committed[0] = true;
            }
            return current;
        });
        return committed[0];
    }

    /**
     * Attaches the auth state to the connection, but only while it is still current. Returns false
     * when the connection has already ended or been superseded, in which case the state is dropped
     * on the floor - which is exactly right, since nobody can reach it any more.
     *
     * <p>Ordering against other operations for the same UUID is the caller's job: the join pipeline
     * calls this from inside its {@link CommitLease}.
     */
    public boolean attachAuthState(ConnectionHandle handle, AuthState state) {
        return commitIfCurrent(handle, () -> handle.setAuthState(state));
    }

    /** Registers this connection's login-timeout task, but only while the connection is current. */
    public boolean attachLoginTimeout(ConnectionHandle handle, ConnectionHandle.Cancellable task) {
        boolean attached = commitIfCurrent(handle, () -> handle.setLoginTimeout(task));
        if (!attached) {
            // The connection ended while we were scheduling: cancel immediately so a stale timeout
            // can never fire against whoever holds the UUID next.
            task.cancel();
        }
        return attached;
    }

    /**
     * Ends the connection for {@code uuid}, but only when it is the one {@code connection} opened.
     * A {@code DisconnectEvent} from an older socket therefore leaves a newer connection intact.
     *
     * <p>Never waits for a commit in flight - a disconnect has to be immediate. An operation that
     * already holds the UUID's slot runs to completion afterwards and simply finds itself stale.
     *
     * @return the handle that was removed, or empty when nothing matched
     */
    public Optional<ConnectionHandle> endIfCurrent(UUID uuid, Object connection) {
        ConnectionHandle[] ended = new ConnectionHandle[1];
        connections.computeIfPresent(uuid, (id, handle) -> {
            if (!handle.isFor(connection)) {
                return handle; // a newer connection owns this UUID; leave it alone
            }
            ended[0] = handle;
            handle.cancelLoginTimeout();
            return null; // remove
        });
        return Optional.ofNullable(ended[0]);
    }

    /**
     * Cancels the resources of a connection that {@link #begin} displaced. The handle is already
     * out of the map, so this only stops its login timeout from firing.
     */
    public void releaseSuperseded(ConnectionHandle superseded) {
        if (superseded != null) {
            superseded.cancelLoginTimeout();
        }
    }

    /** Ends a specific handle, whether or not we still have the {@code Player} that opened it. */
    public boolean end(ConnectionHandle handle) {
        if (handle == null) {
            return false;
        }
        boolean[] ended = new boolean[1];
        connections.computeIfPresent(handle.uuid(), (id, current) -> {
            if (current != handle) {
                return current;
            }
            ended[0] = true;
            handle.cancelLoginTimeout();
            return null;
        });
        return ended[0];
    }

    /** Number of connections currently tracked. Returns to zero once every player has left. */
    public int size() {
        return connections.size();
    }
}

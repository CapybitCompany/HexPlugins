package hex.limbo.auth;

import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Keeps a connection's <em>actual</em> backend in step with the last auth operation that decided
 * where it belongs, and makes sure it gets there or the connection is closed.
 *
 * <h2>Why starting a transfer is not enough</h2>
 * The commit order and the operation revision make sure only the newest operation's routing
 * decision is ever <em>issued</em>. They say nothing about what happens afterwards: a Velocity
 * connection request is asynchronous, and while one is running a second one is rejected outright
 * with {@code CONNECTION_IN_PROGRESS}. Two orderings then end badly:
 *
 * <ul>
 *     <li>a {@code /login} starts a transfer to the target; the {@code /logout} that follows tries
 *     to send the player to the limbo, is rejected, and the target transfer completes - leaving an
 *     unauthenticated player on a real backend;</li>
 *     <li>a {@code /logout} starts a transfer to the limbo; the {@code /login} that follows is
 *     rejected, and the limbo transfer completes - leaving an authenticated player sitting in the
 *     limbo, where no prompt is ever shown because they <em>are</em> authenticated.</li>
 * </ul>
 *
 * <h2>What this class guarantees</h2>
 * Each connection has one desired destination and at most one transfer in flight. A decision that
 * arrives while a transfer is running is not issued and not lost either: it replaces the desired
 * destination, and when the running transfer finishes the newest wish is evaluated again and
 * executed if it has not been reached. Hence the end-to-end invariant:
 *
 * <blockquote>once every started connection request has completed, the live connection is on the
 * backend belonging to the newest valid auth/routing operation, and no older transfer can overwrite
 * that.</blockquote>
 *
 * <h2>Every attempt reaches a terminal state</h2>
 * A transfer that does not arrive is retried, at most {@link #MAX_ATTEMPTS} times per decision and
 * with a growing delay, through an injected {@link Scheduler}. Every attempt is also covered by a
 * watchdog, so a {@link CompletionStage} that Velocity never completes cannot leave the connection
 * half-routed forever. When the attempts are used up the decision is settled as exactly one of
 * {@link RouteResult#FAILED_DISCONNECTED}, {@link RouteResult#FAILED_CONNECTION_KEPT} or
 * {@link RouteResult#FAILED_DISCONNECT_UNKNOWN}, and if the connection is <b>unauthenticated, not
 * confirmed in the limbo and not in the hands of a newer limbo decision, it is disconnected</b>.
 * That is the fail-closed edge of the whole plugin: an unauthenticated player who cannot be put
 * into the limbo must not be left sitting on a real backend because a transfer failed.
 *
 * <h2>Linearization point</h2>
 * <b>A routing decision is linearized when {@link #route} records it as the desired destination</b>
 * - which happens inside the caller's per-UUID commit section, so it is ordered with every other
 * auth effect. Everything after that - the transfer, its retries, the watchdog, the settlement of
 * the returned stage - happens outside that section on purpose: the commit slot must never be held
 * across a network round trip, or a slow backend would block that account's authentication for as
 * long as it takes to time out.
 *
 * <h2>Why an old callback is harmless</h2>
 * The bookkeeping hangs off the {@link ConnectionHandle}, exactly like the auth state does, so a
 * completion callback, retry or watchdog from a disconnected or superseded connection physically
 * holds a different object than the connection that replaced it. It cannot move that connection and
 * it cannot clear its routing state. On top of that every callback re-checks that its handle still
 * owns the UUID and that the socket is still the one it was started for, and gives up otherwise.
 */
public final class RouteCoordinator {

    /** Transfers issued for one routing decision before it is given up on. */
    static final int MAX_ATTEMPTS = 3;
    /** Delay before retry n of the same decision. The last value repeats if attempts were raised. */
    private static final long[] RETRY_BACKOFF_MILLIS = {250L, 1_000L};
    /** How long a single transfer may stay unresolved before the watchdog calls it failed. */
    static final long TRANSFER_TIMEOUT_MILLIS = 15_000L;

    /** Where a connection can be sent. */
    public enum Destination {
        /** The configured authenticated target server. */
        TARGET,
        /** The internal void limbo. */
        LIMBO
    }

    /** The outcome Velocity reports for a connection request, plus a local "could not even ask". */
    public enum TransferStatus {
        /** The player is now on the requested server. */
        SUCCESS,
        /** The player was already there; treated as an arrival. */
        ALREADY_CONNECTED,
        /** Refused because another transfer - not one of ours - is running. */
        CONNECTION_IN_PROGRESS,
        /** A plugin cancelled the request. */
        CONNECTION_CANCELLED,
        /** The backend refused or dropped the connection. */
        SERVER_DISCONNECTED,
        /** The request could not be issued at all: no such server, limbo down, transport error. */
        UNAVAILABLE
    }

    /**
     * How one routing decision finally turned out.
     *
     * <p>The three failure shapes are kept apart because they leave the player in completely
     * different places, and a staff report that conflates them is simply untrue: one connection was
     * closed, one is provably still there, and after a throwing disconnect neither is known.
     */
    public enum RouteResult {
        /** The connection was moved to the destination this decision asked for. */
        REACHED,
        /** The connection was provably already there, so no transfer was needed. */
        ALREADY_THERE,
        /** A newer routing decision replaced this one before it was reached. */
        SUPERSEDED,
        /** The connection ended or was superseded while this decision was outstanding. */
        CONNECTION_GONE,
        /** Every attempt failed and the fail-closed disconnect was issued. */
        FAILED_DISCONNECTED,
        /**
         * Every attempt failed and no disconnect was issued, which is known for certain: the
         * connection was genuinely authenticated again, or the destination was the target rather
         * than the limbo, so leaving the player where they are is safe. A connection that has ended
         * or been replaced is <em>not</em> reported here - that is {@link #CONNECTION_GONE}.
         */
        FAILED_CONNECTION_KEPT,
        /**
         * Every attempt failed, the fail-closed disconnect was attempted, and it threw. Whether it
         * had already taken effect before the failure cannot be established, so this state claims
         * neither. Anything that reports to a human must say exactly that.
         */
        FAILED_DISCONNECT_UNKNOWN;

        /** Whether the connection is provably on the destination this decision asked for. */
        public boolean arrived() {
            return this == REACHED || this == ALREADY_THERE;
        }

        /** Whether the destination was not reached. */
        public boolean failed() {
            return this == FAILED_DISCONNECTED || this == FAILED_CONNECTION_KEPT
                    || this == FAILED_DISCONNECT_UNKNOWN;
        }
    }

    /** Delayed execution, so recovery is bounded without a live proxy and testable without sleeps. */
    @FunctionalInterface
    public interface Scheduler {
        /**
         * Runs {@code task} once, after {@code delayMillis}.
         *
         * @return a handle that stops it if it has not run yet
         */
        ConnectionHandle.Cancellable schedule(long delayMillis, Runnable task);
    }

    /** Issues the actual transfer. Implemented by {@code LimboRouter} over Velocity. */
    public interface Transport {
        /**
         * Starts moving {@code connection} to {@code destination}. Must return immediately with a
         * stage that completes when the outcome is known, and must never block. A stage that never
         * completes is handled by the watchdog.
         *
         * <p>The calling context varies and the implementation may not assume any of it: the first
         * attempt of a decision is issued from inside the commit section that made it, while every
         * retry and every attempt that follows a watchdog is issued from the scheduler thread with
         * no commit slot held at all. What all of them share is the connection's {@code RouteState},
         * which is <em>not</em> held while this runs.
         */
        CompletionStage<TransferStatus> send(ConnectionHandle handle, Object connection, Destination destination);

        /**
         * Closes the connection with the given message key. The fail-closed exit.
         *
         * <p>Unlike {@link #send}, this is called with the connection's commit slot <em>and</em> its
         * {@code RouteState} monitor held, so that the decision to close and the close itself cannot
         * be split by an arrival. It must therefore be a short, non-blocking effect that calls
         * nothing back into this class.
         */
        void disconnect(ConnectionHandle handle, Object connection, String messageKey);
    }

    /**
     * One connection's routing bookkeeping. Instantiated by {@link ConnectionHandle}, so it lives
     * and dies with the connection and can never be reached by, or leak into, its replacement.
     *
     * <p>Every field is guarded by the instance's own monitor - a lock far below the per-UUID
     * commit slot, never held while a transfer is started, a task is scheduled or a future is
     * completed, and never held across anything blocking.
     */
    static final class RouteState {
        private Destination desired;
        private Destination arrived;
        private long revision;
        private boolean inFlight;
        private long inFlightTransfer;
        private long transfers;
        private int attempts;
        private CompletableFuture<RouteResult> pending;
        private ConnectionHandle.Cancellable retry;
        private ConnectionHandle.Cancellable watchdog;
    }

    private final ConnectionRegistry connections;
    private final Transport transport;
    private final Scheduler scheduler;
    private final Logger logger;

    public RouteCoordinator(ConnectionRegistry connections, Transport transport,
                            Scheduler scheduler, Logger logger) {
        this.connections = connections;
        this.transport = transport;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /**
     * Records where this connection should be, issues a transfer if none is running, and hands back
     * the stage that settles when <em>this</em> decision is resolved one way or the other.
     *
     * <p>Call it from inside the operation's commit section: it only records and kicks off, and
     * returns without waiting for the network. The returned stage always completes - on arrival, on
     * being superseded by a newer decision, on the recovery running out, or on the connection
     * ending - so a caller that reports to a staff member can wait for the truth instead of
     * announcing an intention.
     */
    public CompletionStage<RouteResult> route(
            ConnectionHandle handle, Object connection, Destination destination) {
        if (!isLive(handle, connection)) {
            return CompletableFuture.completedFuture(RouteResult.CONNECTION_GONE);
        }
        RouteState state = handle.routeState();
        CompletableFuture<RouteResult> settle;
        CompletableFuture<RouteResult> superseded;
        ConnectionHandle.Cancellable staleRetry;
        synchronized (state) {
            if (state.desired == null && !state.inFlight && state.arrived == destination) {
                // Provably there already, and nothing outstanding to change that. Asking Velocity to
                // move a player to the server they are confirmed to be on buys an ALREADY_CONNECTED
                // round trip and, if it happens to fail, an entirely spurious failure to report.
                return CompletableFuture.completedFuture(RouteResult.ALREADY_THERE);
            }
            superseded = state.pending;
            staleRetry = takeRetry(state);
            state.desired = destination;
            state.revision++;
            state.attempts = 0;
            settle = new CompletableFuture<>();
            state.pending = settle;
        }
        // Outside the monitor: completing a future runs its dependents, and cancelling a task calls
        // into the scheduler. Neither may happen while this connection's routing lock is held.
        cancel(staleRetry);
        if (superseded != null) {
            superseded.complete(RouteResult.SUPERSEDED);
        }
        pump(handle, connection);
        return settle;
    }

    /**
     * Records that the connection has actually landed somewhere, and repairs the end state if that
     * is not where it belongs.
     *
     * <p>Two repairs, and deliberately no more:
     * <ul>
     *     <li>a still-wanted destination that has not been reached is requested again - this is how
     *     a wish that lost the {@code CONNECTION_IN_PROGRESS} race is executed as soon as the
     *     foreign transfer is out of the way, without waiting for the retry timer;</li>
     *     <li>an unauthenticated connection that has landed anywhere but the limbo is sent back to
     *     it. Nothing else can legitimately want that: {@code TARGET} is only ever desired by an
     *     operation that authenticated the connection.</li>
     * </ul>
     *
     * <p>An arrival that matches the current wish - or that nothing is waiting on - is accepted as
     * it stands. In particular an authenticated player who used {@code /limbo} is left in the limbo
     * rather than being pushed to the target on the strength of {@code isAuthenticated()} alone.
     *
     * @param arrived the destination the player reached, or {@code null} for any other backend
     * @return whether the player may stay: {@code false} when a corrective transfer was scheduled
     */
    public boolean onArrived(ConnectionHandle handle, Object connection, Destination arrived) {
        if (!isLive(handle, connection)) {
            return false;
        }
        RouteState state = handle.routeState();
        boolean corrected;
        CompletableFuture<RouteResult> settled = null;
        synchronized (state) {
            state.arrived = arrived;
            if (state.desired == arrived) {
                settled = state.pending;
                state.pending = null;
                state.desired = null;
                state.attempts = 0;
            }
            if (arrived != Destination.LIMBO && !handle.isAuthenticated()) {
                state.desired = Destination.LIMBO;
                state.revision++;
                state.attempts = 0;
                if (state.pending == null) {
                    state.pending = new CompletableFuture<>();
                }
            }
            corrected = state.desired != null && state.desired != arrived;
        }
        if (settled != null) {
            settled.complete(RouteResult.REACHED);
        }
        pump(handle, connection);
        return !corrected;
    }

    /**
     * Releases everything this connection's routing holds: the desired destination, any scheduled
     * retry, the watchdog, and the stage anybody is waiting on. Called from the same places that
     * end a connection's prompt state, so nothing survives a disconnect or a supersede.
     */
    public void endConnection(ConnectionHandle handle) {
        if (handle == null) {
            return;
        }
        RouteState state = handle.routeState();
        CompletableFuture<RouteResult> settled;
        ConnectionHandle.Cancellable retry;
        ConnectionHandle.Cancellable watchdog;
        synchronized (state) {
            settled = state.pending;
            state.pending = null;
            retry = takeRetry(state);
            watchdog = takeWatchdog(state);
            state.desired = null;
            state.inFlight = false;
            state.attempts = 0;
        }
        cancel(retry);
        cancel(watchdog);
        if (settled != null) {
            settled.complete(RouteResult.CONNECTION_GONE);
        }
    }

    /**
     * Issues the desired transfer if there is one and nothing is running.
     *
     * @return whether a transfer was started by this call
     */
    private boolean pump(ConnectionHandle handle, Object connection) {
        RouteState state = handle.routeState();
        if (!isLive(handle, connection)) {
            endConnection(handle);
            return false;
        }
        Destination next;
        long revision;
        long transfer;
        ConnectionHandle.Cancellable staleRetry;
        synchronized (state) {
            staleRetry = takeRetry(state); // we are going now; the timer has nothing left to do
            if (state.inFlight || state.desired == null) {
                cancel(staleRetry);
                return false;
            }
            next = state.desired;
            revision = state.revision;
            state.inFlight = true;
            state.attempts++;
            transfer = ++state.transfers;
            state.inFlightTransfer = transfer;
        }
        cancel(staleRetry);
        // Outside the monitor on purpose: the transport talks to Velocity, and no lock of ours is
        // held while it does.
        start(handle, connection, next, revision, transfer);
        return true;
    }

    private void start(ConnectionHandle handle, Object connection,
                       Destination destination, long revision, long transfer) {
        RouteState state = handle.routeState();
        // Armed before the request so a transport that answers synchronously still finds it, and
        // so a stage that is never completed cannot leave this connection half-routed forever.
        ConnectionHandle.Cancellable watchdog = scheduler.schedule(TRANSFER_TIMEOUT_MILLIS, () -> {
            logger.warn("Transfer of {} to {} did not report back within {} ms; treating it as failed",
                    handle, destination, TRANSFER_TIMEOUT_MILLIS);
            complete(handle, connection, destination, revision, transfer, TransferStatus.UNAVAILABLE);
        });
        boolean alreadyDone;
        synchronized (state) {
            alreadyDone = state.inFlightTransfer != transfer || !state.inFlight;
            if (!alreadyDone) {
                state.watchdog = watchdog;
            }
        }
        if (alreadyDone) {
            cancel(watchdog);
            return;
        }

        CompletionStage<TransferStatus> stage;
        try {
            stage = transport.send(handle, connection, destination);
        } catch (RuntimeException transportFailure) {
            logger.warn("Could not start a {} transfer for {}: {}",
                    destination, handle, transportFailure.getMessage());
            complete(handle, connection, destination, revision, transfer, TransferStatus.UNAVAILABLE);
            return;
        }
        if (stage == null) {
            complete(handle, connection, destination, revision, transfer, TransferStatus.UNAVAILABLE);
            return;
        }
        stage.whenComplete((status, failure) -> {
            if (failure != null) {
                logger.warn("Transfer of {} to {} failed: {}", handle, destination, failure.toString());
                complete(handle, connection, destination, revision, transfer, TransferStatus.UNAVAILABLE);
                return;
            }
            complete(handle, connection, destination, revision, transfer,
                    status == null ? TransferStatus.UNAVAILABLE : status);
        });
    }

    /**
     * Closes out one transfer and decides what is owed next. Exactly one of the transport callback
     * and the watchdog gets past the identity guard, so a late answer to an abandoned request can
     * neither retry nor settle anything.
     *
     * <p>The statuses are handled deliberately:
     * <ul>
     *     <li>{@code SUCCESS} / {@code ALREADY_CONNECTED} - the destination was reached. If it is
     *     the one currently desired the decision is settled; if the desired destination is a
     *     different one, that is executed now. This is what stops an older transfer from being the
     *     last word.</li>
     *     <li>everything else - the move did not happen. If a newer decision exists it is executed
     *     immediately; otherwise the same decision is retried after a growing delay, up to
     *     {@link #MAX_ATTEMPTS} transfers in total, and then given up on. {@code
     *     CONNECTION_IN_PROGRESS} is included: a foreign transfer usually clears within a retry, and
     *     if it never does the attempts run out rather than waiting for an arrival that may never
     *     come.</li>
     * </ul>
     */
    private void complete(ConnectionHandle handle, Object connection, Destination started,
                          long revision, long transfer, TransferStatus status) {
        RouteState state = handle.routeState();
        boolean arrived = status == TransferStatus.SUCCESS || status == TransferStatus.ALREADY_CONNECTED;
        CompletableFuture<RouteResult> settled = null;
        RouteResult settledAs = null;
        ConnectionHandle.Cancellable watchdog;
        boolean pumpAgain = false;
        long retryDelay = -1L;
        // Filled in only when this call is the one that gives up, and filled in under the very lock
        // that decides so. Taking the decision out here - rather than re-reading it in a second
        // critical section - is what stops a newer decision registered in between from being
        // cleared, failed, or acted on by this one.
        CompletableFuture<RouteResult> terminal = null;
        synchronized (state) {
            if (!state.inFlight || state.inFlightTransfer != transfer) {
                return; // somebody else already closed this attempt out
            }
            state.inFlight = false;
            watchdog = takeWatchdog(state);
            if (arrived) {
                state.arrived = started;
            }
            if (arrived && state.desired == started) {
                // Reached where we currently want to be. Destination, not revision, settles this: a
                // decision that was superseded before it was ever executed is satisfied by the end
                // state just as well.
                settled = state.pending;
                state.pending = null;
                settledAs = status == TransferStatus.ALREADY_CONNECTED
                        ? RouteResult.ALREADY_THERE
                        : RouteResult.REACHED;
                state.desired = null;
                state.attempts = 0;
            } else if (arrived || state.revision != revision) {
                pumpAgain = true; // a different destination is wanted now
            } else if (state.attempts >= MAX_ATTEMPTS) {
                terminal = state.pending;
                // Deliberately no snapshot of anything: by the time the teardown runs, an arrival
                // may have confirmed the player safe, a login may have authenticated them, or a
                // newer decision may have taken responsibility. All of that is re-read at the
                // moment it is acted on, never remembered from here.
                state.pending = null;
                state.desired = null;
                state.attempts = 0;
            } else {
                retryDelay = RETRY_BACKOFF_MILLIS[Math.min(state.attempts - 1, RETRY_BACKOFF_MILLIS.length - 1)];
            }
        }
        cancel(watchdog);
        if (settled != null) {
            settled.complete(settledAs);
        }
        if (!arrived) {
            logger.debug("Transfer of {} to {} ended with {} (attempt bookkeeping continues)",
                    handle, started, status);
        }
        if (pumpAgain) {
            pump(handle, connection);
            return;
        }
        if (terminal != null) {
            abandon(handle, connection, started, status, terminal);
            return;
        }
        if (retryDelay >= 0) {
            scheduleRetry(handle, connection, retryDelay);
        }
    }

    private void scheduleRetry(ConnectionHandle handle, Object connection, long delayMillis) {
        RouteState state = handle.routeState();
        ConnectionHandle.Cancellable task = scheduler.schedule(delayMillis, () -> pump(handle, connection));
        ConnectionHandle.Cancellable stale;
        boolean obsolete;
        synchronized (state) {
            obsolete = state.desired == null || state.inFlight;
            stale = obsolete ? null : takeRetry(state);
            if (!obsolete) {
                state.retry = task;
            }
        }
        cancel(stale);
        if (obsolete) {
            cancel(task);
        }
    }

    /**
     * Ends a decision whose attempts are used up. The decision has already been taken out of the
     * state, so nothing here can touch a newer one - {@code settled} is the future that belongs to
     * <em>this</em> decision and nothing else.
     *
     * <p>The fail-closed disconnect is the one thing that must not race an authentication. It runs
     * inside the connection's own commit order, and what it acts on is the state read there and
     * then: whether the player is genuinely authenticated, whether an arrival has confirmed them in
     * the limbo, and whether a newer limbo decision is still working on delivering them. No auth
     * operation id is consulted, because a fresh operation id is minted by a wrong password, a
     * locked account or a lookup miss just as readily as by a successful login, and none of those
     * makes anybody safe.
     *
     * <p>Whatever happens - a throwing transport, a throwing registry - the decision is settled
     * exactly once and nothing is left pending.
     */
    private void abandon(ConnectionHandle handle, Object connection, Destination destination,
                         TransferStatus status, CompletableFuture<RouteResult> settled) {
        logger.warn("Giving up on moving {} to {} after {} attempts (last status {})",
                handle, destination, MAX_ATTEMPTS, status);
        // An honest unknown, not a comfortable default: anything that stops us establishing the
        // state must not be reported as "the connection is still there".
        RouteResult result = RouteResult.FAILED_DISCONNECT_UNKNOWN;
        try {
            result = concludeFailedDecision(handle, connection, destination);
        } catch (RuntimeException terminalFailure) {
            logger.error("Terminal handling of the {} decision for {} threw; the connection state "
                    + "could not be established", destination, handle, terminalFailure);
        } finally {
            settled.complete(result);
        }
    }

    /**
     * Performs the terminal step of a decision whose attempts are used up, and reports what really
     * happened - the only thing a message to a human may be built from.
     *
     * <p><b>The terminal path always enters the connection's current order, as long as the socket
     * still exists.</b> It deliberately does not carry the operation that asked for the route: an
     * operation id proves only that <em>something</em> happened since, not that the player is safe.
     * A wrong password, a locked account, a rate limit and a lookup miss all mint a fresh operation
     * while leaving the player exactly as unauthenticated as they were, and gating the safety check
     * on that id is how an unauthenticated player ends up parked on a real backend. What the check
     * is allowed to rely on is the state itself, read at the moment it acts.
     */
    private RouteResult concludeFailedDecision(ConnectionHandle handle, Object connection,
                                               Destination destination) {
        if (destination != Destination.LIMBO) {
            // Failing to reach the target is not unsafe; the player stays where they are.
            return RouteResult.FAILED_CONNECTION_KEPT;
        }
        RouteResult[] outcome = {null};
        Runnable teardown = () -> outcome[0] = decideFailClosed(handle, connection);

        if (connections.holdsCommitOrder(handle.uuid())) {
            // Reached inline from a transport that answered synchronously, so we are already inside
            // this connection's order and must not try to enter it twice.
            if (!connections.isCurrent(handle) || !handle.isFor(connection)) {
                return RouteResult.CONNECTION_GONE;
            }
            teardown.run();
            return outcome[0] == null ? RouteResult.FAILED_DISCONNECT_UNKNOWN : outcome[0];
        }
        ConnectionRegistry.ApplyOutcome applied = connections.runInOrder(handle, connection, teardown);
        return switch (applied) {
            case APPLIED -> outcome[0] == null ? RouteResult.FAILED_DISCONNECT_UNKNOWN : outcome[0];
            // The connection ended, was replaced, or never belonged to this socket. Saying it is
            // "still open" would be wrong about the very connection this decision was for.
            case STALE_CONNECTION -> RouteResult.CONNECTION_GONE;
            // runInOrder returns neither, and an outcome we cannot interpret is not evidence that
            // anything is fine.
            case OVERTAKEN, UNSTAMPED, NO_EFFECT -> RouteResult.FAILED_DISCONNECT_UNKNOWN;
        };
    }

    /**
     * The fail-closed decision itself, taken and acted on atomically.
     *
     * <h2>The invariant</h2>
     * An unauthenticated connection may only be left alone when it is <em>provably</em> safe:
     * confirmed in the limbo, genuinely authenticated, or in the hands of a newer routing decision
     * that is actively working on delivering it there. Everything else closes it. "A newer
     * operation exists" is not on that list and never decides this.
     *
     * <h2>Ordering</h2>
     * Runs with the UUID's commit slot already held, and takes this connection's {@link RouteState}
     * monitor for the whole decision. <b>The lock order is always commit slot then route state</b>,
     * which is the order every other path uses - {@code route()} is called from inside a commit
     * section, and {@code complete()}, {@code pump()} and {@code onArrived()} release the route
     * state before they touch anything that takes the slot. The reverse order is never taken
     * anywhere, so there is no cycle to deadlock on.
     *
     * <p>Holding the route state across {@link Transport#disconnect} is what linearizes this against
     * {@link #onArrived}: either the arrival records {@code LIMBO} first and this sees it and stands
     * down, or this closes the connection first and the arrival that follows finds nothing left to
     * revive. The disconnect is a single non-blocking packet write and calls nothing back here.
     */
    private RouteResult decideFailClosed(ConnectionHandle handle, Object connection) {
        RouteState state = handle.routeState();
        synchronized (state) {
            if (state.arrived == Destination.LIMBO) {
                // An arrival confirmed them safe. They are where this decision wanted them, however
                // they got there.
                logger.debug("Standing down from the fail-closed teardown of {}: confirmed in the limbo",
                        handle);
                return RouteResult.ALREADY_THERE;
            }
            if (handle.isAuthenticated()) {
                // Genuinely authenticated again - not merely "some newer operation ran". It is not
                // ours to close any more.
                logger.debug("Skipping the fail-closed teardown of {}: it authenticated meanwhile", handle);
                return RouteResult.FAILED_CONNECTION_KEPT;
            }
            if (state.desired == Destination.LIMBO && hasActiveRecovery(state)) {
                // A newer decision has taken responsibility for getting them there and is still
                // working on it. Closing the connection now would pre-empt a recovery that may well
                // succeed; if it does not, that decision's own terminal path closes it.
                logger.debug("Leaving the fail-closed teardown of {} to the newer limbo decision", handle);
                return RouteResult.SUPERSEDED;
            }
            logger.warn("Disconnecting {}: unauthenticated, not in the limbo, and nothing else is "
                    + "delivering them there", handle);
            try {
                transport.disconnect(handle, connection, "disconnect.limbo-unavailable");
            } catch (RuntimeException disconnectFailure) {
                logger.error("Fail-closed disconnect of {} threw; whether it took effect before the "
                        + "failure cannot be established", handle, disconnectFailure);
                return RouteResult.FAILED_DISCONNECT_UNKNOWN;
            }
            return RouteResult.FAILED_DISCONNECTED;
        }
    }

    /** Whether a decision is still actively being worked on. Call under the route state monitor. */
    private static boolean hasActiveRecovery(RouteState state) {
        return state.pending != null || state.inFlight || state.retry != null || state.watchdog != null;
    }

    private static ConnectionHandle.Cancellable takeRetry(RouteState state) {
        ConnectionHandle.Cancellable task = state.retry;
        state.retry = null;
        return task;
    }

    private static ConnectionHandle.Cancellable takeWatchdog(RouteState state) {
        ConnectionHandle.Cancellable task = state.watchdog;
        state.watchdog = null;
        return task;
    }

    private static void cancel(ConnectionHandle.Cancellable task) {
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isLive(ConnectionHandle handle, Object connection) {
        return handle != null && connections.isCurrent(handle) && handle.isFor(connection);
    }

    // ------------------------------------------------------------------ diagnostics

    /** Test/diagnostic hook: the destination this connection still wants to reach, if any. */
    public Destination desiredRoute(ConnectionHandle handle) {
        RouteState state = handle.routeState();
        synchronized (state) {
            return state.desired;
        }
    }

    /** Test/diagnostic hook: whether a transfer is currently running for this connection. */
    public boolean isTransferInFlight(ConnectionHandle handle) {
        RouteState state = handle.routeState();
        synchronized (state) {
            return state.inFlight;
        }
    }

    /** Test/diagnostic hook: transfers issued for the decision currently outstanding. */
    public int attempts(ConnectionHandle handle) {
        RouteState state = handle.routeState();
        synchronized (state) {
            return state.attempts;
        }
    }

    /** Test/diagnostic hook: whether anything is still waiting on this connection's routing. */
    public boolean hasPendingWork(ConnectionHandle handle) {
        RouteState state = handle.routeState();
        synchronized (state) {
            return state.desired != null || state.inFlight || state.pending != null
                    || state.retry != null || state.watchdog != null;
        }
    }
}

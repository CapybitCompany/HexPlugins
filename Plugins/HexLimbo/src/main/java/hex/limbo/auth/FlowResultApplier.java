package hex.limbo.auth;

import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Turns an {@link AuthFlow.Result} into what the player actually sees, in the order the auth
 * operations were committed in.
 *
 * <h2>Why this is not just "send the message"</h2>
 * A flow releases its commit section when it returns, and the caller applies the result afterwards.
 * Between those two points another operation on the same connection can commit <em>and</em> be
 * applied in full. Delivering the older result at that point contradicts what the player has
 * already been told:
 *
 * <ul>
 *     <li>an old {@code /login} would report success and pull the player back out of the limbo a
 *     later {@code /logout} had just put them in;</li>
 *     <li>an old {@code /logout} would send an authenticated player back to the limbo, where they
 *     get no prompt because they are authenticated.</li>
 * </ul>
 *
 * <p>Checking "is this connection still current" cannot separate those two: both operations belong
 * to the same, still-live connection. What separates them is the operation id on the result's
 * {@link ConnectionRegistry.OperationStamp}, re-validated inside the very section the commits use -
 * see {@link ConnectionRegistry#applyOrdered}.
 *
 * <h2>Linearization point of the player-facing effects</h2>
 * <b>The effects of a result are linearized at the moment {@link ConnectionRegistry#applyOrdered}
 * acquires the UUID's slot for them.</b> They run to completion inside that section, so they are
 * totally ordered with every commit for the same account, and they run at all only while the
 * stamped operation is still the connection's latest. Hence the invariant: <em>if operation B
 * committed after operation A, a late result from A can no longer send a message or perform any
 * routing once B has taken effect.</em>
 *
 * <p>The Velocity binding is left to the caller through {@link Effects} - the decision of
 * <em>whether</em> and <em>what</em> lives here, the {@code Player} call lives in the command layer.
 */
public final class FlowResultApplier {

    /**
     * The player-facing side effects a flow result can have, with Velocity kept on the other side of
     * the interface.
     *
     * <p>Implementations must be cheap and non-blocking: they run inside the connection's ordered
     * section, which the next auth operation for that account is waiting on.
     */
    public interface Effects {

        /** Sends the message the result carries. */
        void sendMessage(String messageKey, Object[] args);

        /** Disconnects the player with the message the result carries. */
        void disconnect(String messageKey, Object[] args);

        /**
         * Asks for the player to be sent to the configured target server.
         *
         * <p>No operation stamp travels with a routing decision on purpose. The decision outlives
         * the operation that made it - it survives retries, a watchdog and a terminal fail-closed
         * check - and by then an operation id proves only that <em>something</em> happened, not that
         * the player is safe. {@link RouteCoordinator} re-reads the real auth and arrival state at
         * the moment it acts instead.
         *
         * @return the stage that settles when that decision is resolved - reached, superseded,
         *         failed or the connection gone. Issuing a request is not arriving, and a caller
         *         that reports an outcome has to wait for this rather than assume one.
         */
        CompletionStage<RouteCoordinator.RouteResult> sendToTarget();

        /** Asks for the player to be sent back to the limbo. See {@link #sendToTarget}. */
        CompletionStage<RouteCoordinator.RouteResult> sendToLimbo();
    }

    /**
     * What applying a result did, and - when it routed - how that transfer ends.
     *
     * <p>{@link #routing()} is empty when nothing was routed. It is deliberately a separate answer
     * from {@link #outcome()}: {@code APPLIED} only means the decision was handed to the router in
     * order, never that the player has arrived anywhere.
     */
    public record Application(ConnectionRegistry.ApplyOutcome outcome,
                              Optional<CompletionStage<RouteCoordinator.RouteResult>> routing) {

        /** An application that routed nothing. */
        public static Application of(ConnectionRegistry.ApplyOutcome outcome) {
            return new Application(outcome, Optional.empty());
        }

        /** Whether the effects were actually performed. */
        public boolean applied() {
            return outcome.applied();
        }
    }

    private FlowResultApplier() {}

    /**
     * Applies {@code result} to {@code connection} if - and only if - the operation that produced it
     * is still that connection's latest.
     *
     * <p>Fail-closed in every other case: a result with nothing to do, a result without a stamp, a
     * disconnected or superseded connection, a different {@code Player} instance, or an operation
     * that has been overtaken all leave the player untouched.
     *
     * @return what happened, so a caller who has to report the outcome can distinguish "done" from
     *         "overtaken by a newer operation" from "that connection is gone"
     */
    public static Application apply(
            ConnectionRegistry connections,
            AuthFlow.Result result,
            Object connection,
            Effects effects
    ) {
        if (!result.hasEffects()) {
            return Application.of(ConnectionRegistry.ApplyOutcome.NO_EFFECT);
        }
        @SuppressWarnings("unchecked")
        CompletionStage<RouteCoordinator.RouteResult>[] routing = new CompletionStage[1];
        ConnectionRegistry.ApplyOutcome outcome = connections.applyOrdered(
                result.stamp().orElse(null), connection, () -> routing[0] = run(result, effects));
        return new Application(outcome, Optional.ofNullable(routing[0]));
    }

    /**
     * Runs one asynchronous flow attempt end to end, and is the single path all five player
     * commands take: execute the flow, apply its result in order, and - if it throws - log the
     * failure in full and deliver {@code error.internal} under exactly the same ordering rules.
     *
     * <p><b>Why the failure message needs ordering too.</b>
     * A flow that throws has already released its commit section by the time the exception is
     * caught, so a second operation on the same connection can commit and be applied before the
     * catch block runs. Sending the error line straight to the player would put it after a message
     * describing something newer - or onto a socket that has since disconnected or been replaced.
     *
     * <p>The failed attempt is identified by the operation it <em>really</em> ran as, claimed at the
     * linearization point and remembered per thread, so nothing has to be predicted. Two shapes of
     * failure fall out of that, and both are honest:
     *
     * <ul>
     *     <li><b>It threw inside its commit section.</b> The stamp is that section's, and the usual
     *     rule decides: shown when that operation is still the connection's latest, suppressed when
     *     a newer one has overtaken it. An attempt that waited a long time for the slot and then
     *     failed is the newest operation and does get reported.</li>
     *     <li><b>It threw before reaching a commit section</b> - a Mojang lookup in {@code /register},
     *     say. There is no operation to be ordered against, so the question becomes whether anything
     *     at all has happened to this connection since the attempt started. The count observed
     *     beforehand is compared with the count now; unchanged means nothing overtook it and the
     *     player is told.</li>
     * </ul>
     *
     * <p>Either way the connection and the socket are re-checked, and the exception is logged in
     * full whether or not anybody is told about it.
     *
     * <p>The one thing this relies on: a flow runs at most one auth operation of its own, on the
     * calling thread. Every flow in {@link AuthFlow} does - the only work any of them performs
     * before taking their commit slot is the Mojang name lookup, which touches no connection state.
     * A flow that ran a <em>second</em> connection's operation inline before failing would hand its
     * error that operation's stamp.
     *
     * @param label what to call this flow in the log, e.g. {@code "/login"}
     * @return the outcome of applying the result, or of reporting the failure
     */
    public static Application execute(
            ConnectionRegistry connections,
            ConnectionHandle handle,
            Object connection,
            Effects effects,
            String label,
            Logger logger,
            Supplier<AuthFlow.Result> flow
    ) {
        long operationsBefore = handle.currentOperation();
        // Only operations this attempt itself enters are recorded, and the scope - two longs, no
        // connection references - is removed again on every path.
        try (ConnectionRegistry.FailureObservation observed = connections.observeOperations()) {
            try {
                return apply(connections, flow.get(), connection, effects);
            } catch (RuntimeException failure) {
                // Logged in full, always: a database or programming error must never be swallowed
                // just because the player is not going to be told about it.
                logger.error("{} failed asynchronously for {}", label, handle, failure);
                ConnectionRegistry.OperationStamp stamp = observed.operationFor(handle)
                        .orElseGet(() -> new ConnectionRegistry.OperationStamp(handle, operationsBefore));
                return Application.of(connections.applyOrdered(stamp, connection,
                        () -> effects.sendMessage("error.internal", new Object[0])));
            }
        }
    }

    private static CompletionStage<RouteCoordinator.RouteResult> run(AuthFlow.Result result, Effects effects) {
        if (result.routing() == AuthFlow.Routing.DISCONNECT) {
            // The kick carries the message itself; a chat line the player would never get to read
            // has no business racing the disconnect packet.
            result.messageKey().ifPresent(key -> effects.disconnect(key, result.args()));
            return null;
        }
        result.messageKey().ifPresent(key -> effects.sendMessage(key, result.args()));
        return switch (result.routing()) {
            case TARGET -> effects.sendToTarget();
            case LIMBO -> effects.sendToLimbo();
            case DISCONNECT, NONE -> null; // DISCONNECT is handled above; NONE has nothing to do
        };
    }
}

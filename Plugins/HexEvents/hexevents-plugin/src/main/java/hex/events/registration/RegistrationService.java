package hex.events.registration;

import hex.events.api.*;
import hex.events.model.EventDefinition;
import hex.events.model.EventInstance;
import hex.events.persistence.AdmissionRepository;
import hex.events.persistence.PaymentRepository;
import hex.events.persistence.PersistenceExecutor;
import hex.events.persistence.RegistrationRepository;
import hex.events.registry.CostProviderRegistry;
import hex.events.registry.EventModuleRegistry;
import hex.events.registry.RequirementProviderRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registration/payment orchestration.
 *
 * JDBC is never executed on the Bukkit primary thread. Bukkit/inventory providers
 * are explicitly switched back to the primary thread, while DB-safe providers
 * (currently HexEconomy) execute on HexCore's async DB executor.
 */
public final class RegistrationService {
    private final Plugin plugin;
    private final RequirementProviderRegistry requirements;
    private final CostProviderRegistry costs;
    private final RegistrationRepository registrations;
    private final PaymentRepository payments;
    private final AdmissionRepository admissions;
    private final EventModuleRegistry modules;
    private final PersistenceExecutor persistence;
    private final EventQueuePriorityResolver priorityResolver = new EventQueuePriorityResolver();
    private final Set<String> locks = ConcurrentHashMap.newKeySet();
    private volatile RegistrationObserver observer = RegistrationObserver.NOOP;

    public RegistrationService(Plugin plugin, RequirementProviderRegistry requirements, CostProviderRegistry costs,
                               RegistrationRepository registrations, PaymentRepository payments,
                               AdmissionRepository admissions, EventModuleRegistry modules,
                               PersistenceExecutor persistence) {
        this.plugin = plugin;
        this.requirements = requirements;
        this.costs = costs;
        this.registrations = registrations;
        this.payments = payments;
        this.admissions = admissions;
        this.modules = modules;
        this.persistence = persistence;
    }

    public void setObserver(RegistrationObserver observer) {
        this.observer = observer == null ? RegistrationObserver.NOOP : observer;
    }

    public CompletableFuture<RegistrationResult> registerAsync(Player player, EventInstance instance) {
        String lock = lock(instance.id(), player.getUniqueId());
        if (!locks.add(lock)) return CompletableFuture.completedFuture(RegistrationResult.fail("Operacja zapisu już trwa."));

        RegistrationResult precheck = registerPrecheck(player, instance);
        if (!precheck.success()) {
            locks.remove(lock);
            return CompletableFuture.completedFuture(precheck);
        }

        PlayerContext ctx = new PlayerContext(player.getUniqueId(), player.getName());
        RegistrationResult requirementsResult = validateRequirements(ctx, instance.definition());
        if (!requirementsResult.success()) {
            locks.remove(lock);
            return CompletableFuture.completedFuture(requirementsResult);
        }

        List<Charged> charged = new ArrayList<>();
        String attemptId = UUID.randomUUID().toString();
        EventQueuePriority prioritySnapshot = priorityResolver.resolve(player);

        CompletableFuture<RegistrationResult> workflow = validateCostsAsync(ctx, instance.definition())
                .thenCompose(costValidation -> {
                    if (!costValidation.success()) return CompletableFuture.completedFuture(costValidation);
                    return chargeAll(ctx, instance, attemptId, charged, 0)
                            .thenCompose(ignored -> persistence.submit(() ->
                                    registrations.upsertRegisteredWithAdmission(instance.id(), player.getUniqueId(), player.getName(), prioritySnapshot)))
                            .thenCompose(registeredAt -> onMain(() -> {
                                instance.rememberRegistration(player.getUniqueId(), player.getName(), registeredAt);
                                observer.onRegistered(player, instance, prioritySnapshot);
                                return RegistrationResult.ok("Zapisano na wydarzenie. Miejsce zostanie przydzielone według priorytetu kolejki.");
                            }));
                });

        return workflow.handle((result, error) -> new Outcome(result, error))
                .thenCompose(outcome -> {
                    if (outcome.error == null) return CompletableFuture.completedFuture(outcome.result);
                    return compensateKnownChargesAsync(ctx, charged)
                            .handle((ignored, compensationError) -> {
                                Throwable root = unwrap(outcome.error);
                                String message = root instanceof ReconciliationException
                                        ? rootMessage(root)
                                        : "Nie udało się zapisać: " + rootMessage(root);
                                if (compensationError != null) {
                                    plugin.getLogger().severe("Kompensacja kosztów po nieudanym zapisie wymaga uwagi: " + rootMessage(compensationError));
                                }
                                return RegistrationResult.fail(message);
                            });
                })
                .whenComplete((ignored, error) -> locks.remove(lock));
    }

    public CompletableFuture<RegistrationResult> cancelAsync(Player player, EventInstance instance) {
        String lock = lock(instance.id(), player.getUniqueId());
        if (!locks.add(lock)) return CompletableFuture.completedFuture(RegistrationResult.fail("Operacja zapisu już trwa."));

        RegistrationResult precheck = cancelPrecheck(player, instance);
        if (!precheck.success()) {
            locks.remove(lock);
            return CompletableFuture.completedFuture(precheck);
        }

        PlayerContext ctx = new PlayerContext(player.getUniqueId(), player.getName());
        return refundPlayerAsync(ctx, instance.id(), player.getUniqueId())
                .thenCompose(outcome -> {
                    String status = switch (outcome) {
                        case COMPLETE -> "CANCELLED";
                        case PENDING -> "CANCEL_REFUND_PENDING";
                        case RECONCILIATION_REQUIRED -> "CANCEL_REFUND_RECONCILIATION";
                    };
                    return persistence.write(() -> registrations.markClosed(instance.id(), player.getUniqueId(), status))
                            .thenCompose(ignored -> onMain(() -> {
                                instance.forgetRegistration(player.getUniqueId());
                                observer.onCancelled(player, instance);
                                return switch (outcome) {
                                    case COMPLETE -> RegistrationResult.ok("Anulowano zapis i zwrócono koszt.");
                                    case PENDING -> RegistrationResult.ok("Anulowano zapis. Zwrot oczekuje na dostarczenie.");
                                    case RECONCILIATION_REQUIRED -> RegistrationResult.ok("Anulowano zapis. Zwrot wymaga bezpiecznej rekoncyliacji przez administrację.");
                                };
                            }));
                })
                .exceptionally(error -> RegistrationResult.fail("Nie udało się anulować zapisu: " + rootMessage(error)))
                .whenComplete((ignored, error) -> locks.remove(lock));
    }

    public CompletableFuture<RefundOutcome> refundForCapacityAsync(Player player, EventInstance instance) {
        if (player == null || !player.isOnline()) return CompletableFuture.completedFuture(RefundOutcome.PENDING);
        String lock = lock(instance.id(), player.getUniqueId());
        if (!locks.add(lock)) return CompletableFuture.completedFuture(RefundOutcome.PENDING);

        PlayerContext ctx = new PlayerContext(player.getUniqueId(), player.getName());
        return refundPlayerAsync(ctx, instance.id(), player.getUniqueId())
                .thenCompose(outcome -> {
                    String status = switch (outcome) {
                        case COMPLETE -> "QUEUE_REFUNDED";
                        case PENDING -> "QUEUE_REFUND_PENDING";
                        case RECONCILIATION_REQUIRED -> "QUEUE_REFUND_RECONCILIATION";
                    };
                    return persistence.write(() -> registrations.markClosed(instance.id(), player.getUniqueId(), status))
                            .thenCompose(ignored -> onMain(() -> {
                                instance.forgetRegistration(player.getUniqueId());
                                return outcome;
                            }));
                })
                .exceptionally(error -> {
                    plugin.getLogger().severe("Capacity refund failed: " + rootMessage(error));
                    return RefundOutcome.RECONCILIATION_REQUIRED;
                })
                .whenComplete((ignored, error) -> locks.remove(lock));
    }

    /** Runtime state changes are immediate; their durable representation is queued asynchronously. */
    public void forfeit(EventInstance instance, UUID playerId, String terminalStatus) {
        instance.forgetRegistration(playerId);
        persistence.fireAndForget("forfeit:" + instance.id() + ":" + playerId, () -> {
            payments.markForfeited(instance.id(), playerId);
            registrations.markClosed(instance.id(), playerId, terminalStatus);
        });
    }

    public CompletableFuture<Void> refundAllAsync(EventInstance instance) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (UUID playerId : List.copyOf(instance.registeredPlayers())) {
            Player online = Bukkit.getPlayer(playerId);
            String name = online != null ? online.getName() : instance.registrationName(playerId);
            if (name == null) name = "";
            String finalName = name;
            chain = chain.thenCompose(ignored -> refundPlayerAsync(new PlayerContext(playerId, finalName), instance.id(), playerId)
                    .thenCompose(outcome -> persistence.write(() -> registrations.markClosed(instance.id(), playerId, switch (outcome) {
                        case COMPLETE -> "SYSTEM_REFUNDED";
                        case PENDING -> "SYSTEM_REFUND_PENDING";
                        case RECONCILIATION_REQUIRED -> "SYSTEM_REFUND_RECONCILIATION";
                    })))
                    .thenCompose(ignored2 -> onMain(() -> {
                        instance.forgetRegistration(playerId);
                        return null;
                    })));
        }
        return chain;
    }

    public CompletableFuture<Set<UUID>> retryPendingRefundsAsync(Player player) {
        PlayerContext ctx = new PlayerContext(player.getUniqueId(), player.getName());
        return persistence.read(() -> payments.loadPendingRefunds(player.getUniqueId()))
                .thenCompose(rows -> {
                    Set<UUID> affected = new LinkedHashSet<>();
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (PaymentRepository.PaymentRow row : rows) {
                        affected.add(row.instanceId());
                        chain = chain.thenCompose(ignored -> refundOneAsync(ctx, row).thenApply(outcome -> null));
                    }
                    return chain.thenCompose(ignored -> finalizePendingRefunds(player.getUniqueId(), affected));
                });
    }

    public RegistrationResult checkEligibility(Player player, EventInstance instance) {
        return validateRequirements(new PlayerContext(player.getUniqueId(), player.getName()), instance.definition());
    }

    public boolean canCancel(EventInstance instance) {
        return switch (instance.definition().registration().cancelUntil()) {
            case NEVER -> false;
            case START -> Instant.now().isBefore(instance.startAt());
            case LOBBY_START -> Instant.now().isBefore(instance.lobbyAt());
        };
    }

    private RegistrationResult registerPrecheck(Player player, EventInstance instance) {
        EventDefinition def = instance.definition();
        Instant now = Instant.now();
        if (!def.registration().enabled()) return RegistrationResult.fail("Ten event nie używa zapisów.");
        if (modules.find(def.moduleId()).isEmpty()) return RegistrationResult.fail("Moduł eventu jest obecnie niedostępny.");
        if (!now.isBefore(instance.startAt())) return RegistrationResult.fail("Zapisy są już zamknięte.");
        if (instance.registeredPlayers().contains(player.getUniqueId())) return RegistrationResult.ok("Jesteś już zapisany.");
        return RegistrationResult.ok("OK");
    }

    private RegistrationResult cancelPrecheck(Player player, EventInstance instance) {
        if (!instance.registeredPlayers().contains(player.getUniqueId())) return RegistrationResult.fail("Nie jesteś zapisany.");
        if (!canCancel(instance)) return RegistrationResult.fail("Nie można już anulować zapisu.");
        if (!observer.canCancel(player, instance)) return RegistrationResult.fail("Masz już przydzielone miejsce. Opuszczenie eventu oznacza utratę opłaty i prawa powrotu.");
        return RegistrationResult.ok("OK");
    }

    private RegistrationResult validateRequirements(PlayerContext ctx, EventDefinition def) {
        for (EventDefinition.RequirementSpec spec : def.requirements()) {
            RequirementProvider provider = requirements.find(spec.type()).orElse(null);
            if (provider == null || !provider.available()) return RegistrationResult.fail("Wymaganie jest niedostępne: " + spec.type());
            RequirementCheck check = provider.check(ctx, spec.settings());
            if (!check.success()) return RegistrationResult.fail(check.message());
        }
        return RegistrationResult.ok("OK");
    }

    private CompletableFuture<RegistrationResult> validateCostsAsync(PlayerContext ctx, EventDefinition def) {
        CompletableFuture<RegistrationResult> chain = CompletableFuture.completedFuture(RegistrationResult.ok("OK"));
        for (EventDefinition.CostSpec spec : def.costs()) {
            chain = chain.thenCompose(previous -> {
                if (!previous.success()) return CompletableFuture.completedFuture(previous);
                CostProvider provider = costs.find(spec.type()).orElse(null);
                if (provider == null) return CompletableFuture.completedFuture(RegistrationResult.fail("Koszt jest niedostępny: " + spec.type()));
                return onMain(provider::available).thenCompose(available -> {
                    if (!available) return CompletableFuture.completedFuture(RegistrationResult.fail("Koszt jest niedostępny: " + spec.type()));
                    return invokeProvider(provider, () -> provider.validate(ctx, spec.settings()))
                            .thenApply(check -> check.success() ? RegistrationResult.ok("OK") : RegistrationResult.fail(check.message()));
                });
            });
        }
        return chain;
    }

    private CompletableFuture<Void> chargeAll(PlayerContext ctx, EventInstance instance, String attemptId, List<Charged> charged, int index) {
        if (index >= instance.definition().costs().size()) return CompletableFuture.completedFuture(null);
        EventDefinition.CostSpec spec = instance.definition().costs().get(index);
        CostProvider provider = costs.find(spec.type()).orElseThrow();
        String raw = instance.id() + ":" + ctx.playerId() + ":" + spec.id() + ":" + attemptId;
        String idem = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();

        return persistence.submit(() -> payments.beginCharge(instance.id(), ctx.playerId(), ctx.playerName(), provider.type(), spec.id(), idem))
                .thenCompose(intent -> invokeProvider(provider, () -> provider.charge(ctx, spec.settings(), spec.id(), idem))
                        .handle((result, providerError) -> new ChargeInvocation(intent, result, providerError)))
                .thenCompose(invocation -> {
                    if (invocation.error != null) {
                        return persistence.write(() -> payments.markStatus(invocation.intent.paymentId(), "CHARGE_RECONCILIATION_REQUIRED"))
                                .thenCompose(ignored -> failedFuture(new ReconciliationException("Niepewny wynik pobrania kosztu " + spec.id() + ": " + rootMessage(invocation.error))));
                    }
                    if (invocation.result == null || !invocation.result.success() || invocation.result.receipt() == null) {
                        String message = invocation.result == null ? "Provider zwrócił null" : invocation.result.message();
                        return persistence.write(() -> payments.markStatus(invocation.intent.paymentId(), "CHARGE_FAILED"))
                                .thenCompose(ignored -> failedFuture(new ChargeException(message)));
                    }
                    CostReceipt receipt = invocation.result.receipt();
                    return persistence.write(() -> payments.markCharged(invocation.intent.paymentId(), receipt))
                            .handle((ignored, persistError) -> {
                                if (persistError != null) {
                                    persistence.fireAndForget("charge-reconciliation:" + invocation.intent.paymentId(),
                                            () -> payments.markStatus(invocation.intent.paymentId(), "CHARGE_RECONCILIATION_REQUIRED"));
                                    throw new ReconciliationException("Koszt pobrany, ale nie udało się zatwierdzić receiptu. Wymagana rekoncyliacja.");
                                }
                                charged.add(new Charged(provider, new PaymentRepository.PaymentRow(invocation.intent.paymentId(), invocation.intent.instanceId(),
                                        invocation.intent.playerId(), invocation.intent.playerName(), receipt, idem, "CHARGED")));
                                return null;
                            });
                })
                .thenCompose(ignored -> chargeAll(ctx, instance, attemptId, charged, index + 1));
    }

    private CompletableFuture<Void> compensateKnownChargesAsync(PlayerContext ctx, List<Charged> charged) {
        List<Charged> reverse = new ArrayList<>(charged);
        Collections.reverse(reverse);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Charged chargedCost : reverse) {
            chain = chain.thenCompose(ignored -> refundOneAsync(ctx, chargedCost.row()).thenApply(outcome -> null));
        }
        return chain;
    }

    private CompletableFuture<RefundOutcome> refundPlayerAsync(PlayerContext ctx, UUID instanceId, UUID playerId) {
        return persistence.read(() -> payments.loadRefundable(instanceId, playerId))
                .thenCompose(rows -> {
                    CompletableFuture<RefundAccumulator> chain = CompletableFuture.completedFuture(new RefundAccumulator(false, false));
                    for (PaymentRepository.PaymentRow row : rows) {
                        chain = chain.thenCompose(acc -> refundOneAsync(ctx, row).thenApply(outcome -> acc.add(outcome)));
                    }
                    return chain;
                })
                .thenCompose(acc -> persistence.read(() -> new RefundFlags(
                        payments.hasPendingRefund(instanceId, playerId),
                        payments.hasReconciliationRequired(instanceId, playerId))))
                .thenApply(flags -> flags.reconciliation ? RefundOutcome.RECONCILIATION_REQUIRED
                        : flags.pending ? RefundOutcome.PENDING : RefundOutcome.COMPLETE);
    }

    private CompletableFuture<RefundOutcome> refundOneAsync(PlayerContext ctx, PaymentRepository.PaymentRow row) {
        CostProvider provider = costs.find(row.receipt().providerType()).orElse(null);
        if (provider == null) {
            return persistence.write(() -> payments.markStatus(row.paymentId(), "REFUND_PENDING"))
                    .thenApply(ignored -> RefundOutcome.PENDING);
        }

        return onMain(provider::available)
                .thenCompose(available -> {
                    if (!available) {
                        return persistence.write(() -> payments.markStatus(row.paymentId(), "REFUND_PENDING"))
                                .thenApply(ignored -> RefundOutcome.PENDING);
                    }
                    return persistence.submit(() -> payments.beginRefund(row.paymentId()))
                            .thenCompose(begun -> {
                                if (!begun) {
                                    return persistence.read(() -> payments.hasReconciliationRequired(row.instanceId(), row.playerId()))
                                            .thenApply(reconciliation -> RefundAttempt.immediate(reconciliation
                                                    ? RefundOutcome.RECONCILIATION_REQUIRED : RefundOutcome.PENDING));
                                }
                                return invokeProvider(provider, () -> provider.refund(ctx, row.receipt(), row.idempotencyKey() + ":refund"))
                                        .handle((result, error) -> RefundAttempt.invoked(new RefundInvocation(result, error)));
                            })
                            .thenCompose(attempt -> {
                                if (attempt.immediate != null) return CompletableFuture.completedFuture(attempt.immediate);
                                RefundInvocation invocation = attempt.invocation;
                                if (invocation.error != null) {
                                    return persistence.write(() -> payments.requireRefundReconciliation(row.paymentId()))
                                            .thenApply(ignored -> {
                                                plugin.getLogger().severe("Refund wymaga rekoncyliacji: " + row.paymentId() + " / " + rootMessage(invocation.error));
                                                return RefundOutcome.RECONCILIATION_REQUIRED;
                                            });
                                }
                                CostOperationResult result = invocation.result;
                                if (result != null && result.success()) {
                                    return persistence.write(() -> payments.completeRefund(row.paymentId())).thenApply(ignored -> RefundOutcome.COMPLETE);
                                }
                                if (result != null && result.retryable()) {
                                    return persistence.write(() -> payments.failRefund(row.paymentId())).thenApply(ignored -> RefundOutcome.PENDING);
                                }
                                return persistence.write(() -> payments.requireRefundReconciliation(row.paymentId()))
                                        .thenApply(ignored -> RefundOutcome.RECONCILIATION_REQUIRED);
                            });
                });
    }

    private CompletableFuture<Set<UUID>> finalizePendingRefunds(UUID playerId, Set<UUID> affected) {
        Set<UUID> finalized = new LinkedHashSet<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (UUID instanceId : affected) {
            chain = chain.thenCompose(ignored -> persistence.read(() -> new RefundFlags(
                            payments.hasPendingRefund(instanceId, playerId),
                            payments.hasReconciliationRequired(instanceId, playerId)))
                    .thenCompose(flags -> {
                        if (flags.pending || flags.reconciliation) return CompletableFuture.completedFuture(null);
                        return persistence.write(() -> {
                            registrations.finalizeQueueRefund(instanceId, playerId);
                            admissions.finalizePendingRefund(instanceId, playerId);
                        }).thenRun(() -> finalized.add(instanceId));
                    }));
        }
        return chain.thenApply(ignored -> Set.copyOf(finalized));
    }

    private <T> CompletableFuture<T> invokeProvider(CostProvider provider, Supplier<T> work) {
        return provider.requiresMainThread() ? onMain(work) : persistence.io(work);
    }

    private <T> CompletableFuture<T> onMain(Supplier<T> work) {
        if (Bukkit.isPrimaryThread()) {
            try { return CompletableFuture.completedFuture(work.get()); }
            catch (Throwable error) { return failedFuture(error); }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(work.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static String lock(UUID instanceId, UUID playerId) { return instanceId + ":" + playerId; }
    private record Charged(CostProvider provider, PaymentRepository.PaymentRow row) { }
    private record Outcome(RegistrationResult result, Throwable error) { }
    private record ChargeInvocation(PaymentRepository.PaymentRow intent, CostOperationResult result, Throwable error) { }
    private record RefundInvocation(CostOperationResult result, Throwable error) { }
    private record RefundAttempt(RefundOutcome immediate, RefundInvocation invocation) {
        static RefundAttempt immediate(RefundOutcome outcome) { return new RefundAttempt(outcome, null); }
        static RefundAttempt invoked(RefundInvocation invocation) { return new RefundAttempt(null, invocation); }
    }
    private record RefundFlags(boolean pending, boolean reconciliation) { }
    private record RefundAccumulator(boolean pending, boolean reconciliation) {
        RefundAccumulator add(RefundOutcome outcome) {
            return new RefundAccumulator(pending || outcome == RefundOutcome.PENDING,
                    reconciliation || outcome == RefundOutcome.RECONCILIATION_REQUIRED);
        }
    }
    private static final class ChargeException extends RuntimeException { private ChargeException(String message) { super(message); } }
    private static final class ReconciliationException extends RuntimeException { private ReconciliationException(String message) { super(message); } }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = unwrap(throwable);
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}

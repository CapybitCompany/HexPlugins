package hex.auctionbazaar.audit.service;

import hex.auctionbazaar.audit.model.AuditEntry;
import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.util.MessageFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warstwa uslug do zapisow audytowych. Zapisy dziala asynchronicznie,
 * a blad zapisu nigdy nie moze zatrzymac zaakceptowanej transakcji gracza
 * (blad trafia do konsoli serwera).
 * Formatowanie wpisow dla admina korzysta z messages.yml aby zachowac
 * Polski jezyk i konfigurowalne etykiety.
 */
public final class AuditService {

    private final Logger logger;
    private final HexCoreBridge hexCore;
    private final AuditLogRepository repo;
    private final MessageFactory messages;

    /**
     * Wspólny lock/maszyna stanów (punkt #7). Chroni ATOMOWO cztery rzeczy:
     * przyjmowanie nowych wpisów ({@link #acceptingNew}), rejestrację śledzonego future w
     * {@link #pending}, stop-accepting oraz migawkę pending przy zamykaniu. Dzięki temu
     * {@code awaitPending(stopAcceptingNew=true)} widzi KAŻDY wcześniej zaakceptowany wpis,
     * a żaden insert nie startuje po stop-accepting.
     */
    private final Object stateLock = new Object();
    /** Śledzone, trwające inserty audytu. Każdy dostęp (add/remove/migawka) pod {@link #stateLock}. */
    private final Set<CompletableFuture<Long>> pending = new HashSet<>();
    /** Czy przyjmujemy nowe wpisy. false po {@code awaitPending(stopAcceptingNew=true)}. Pod {@link #stateLock}. */
    private boolean acceptingNew = true;
    /**
     * Wyłącznie do testu wyścigu (punkt #7): bariera uruchamiana MIĘDZY akceptacją wpisu
     * (rejestracją w {@link #pending} pod lockiem) a startem pracy async. Produkcyjnie {@code null}
     * (no-op). Pozwala udowodnić, że rejestracja poprzedza start async.
     */
    volatile Runnable asyncSubmitBarrier;

    public AuditService(Logger logger, HexCoreBridge hexCore, AuditLogRepository repo,
                        MessageFactory messages) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.hexCore = Objects.requireNonNull(hexCore, "hexCore");
        this.repo = Objects.requireNonNull(repo, "repo");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public static final class Builder {
        private UUID actorUuid;
        private String actorName;
        private String action;
        private String market;
        private String itemKey;
        private Long listingId;
        private Long orderId;
        private Long claimId;
        private Long amount;
        private BigDecimal unitPrice;
        private BigDecimal total;
        private String result = "OK";
        private String reason;
        private String metadataJson;

        public Builder actor(UUID uuid, String name) {
            this.actorUuid = uuid;
            this.actorName = name;
            return this;
        }
        public Builder action(String v) { this.action = v; return this; }
        public Builder market(String v) { this.market = v; return this; }
        public Builder itemKey(String v) { this.itemKey = v; return this; }
        public Builder listingId(Long v) { this.listingId = v; return this; }
        public Builder orderId(Long v) { this.orderId = v; return this; }
        public Builder claimId(Long v) { this.claimId = v; return this; }
        public Builder amount(Long v) { this.amount = v; return this; }
        public Builder unitPrice(BigDecimal v) { this.unitPrice = v; return this; }
        public Builder total(BigDecimal v) { this.total = v; return this; }
        public Builder result(String v) { this.result = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder metadataJson(String v) { this.metadataJson = v; return this; }
    }

    public Builder builder() {
        return new Builder();
    }

    /**
     * Nieblokujący zapis - błędy trafiają do logu, ale NIGDY nie są propagowane do transakcji gracza.
     *
     * <p>Kolejność (punkt #7): pod {@link #stateLock} sprawdzamy {@link #acceptingNew} i rejestrujemy
     * śledzony future ({@code tracked}) w {@link #pending} PRZED startem pracy async. Dopiero potem,
     * już poza lockiem, zlecamy właściwy insert. Dzięki temu {@code awaitPending(stopAcceptingNew=true)}
     * albo widzi ten wpis w migawce, albo (gdy wygrał wyścig o lock) ustawił już stop-accepting i wtedy
     * ten {@code log()} nic nie wstawia. Nie ma okna, w którym insert wystartował, a nie jest śledzony.</p>
     */
    public CompletableFuture<Long> log(Builder b) {
        Objects.requireNonNull(b, "builder");
        CompletableFuture<Long> tracked = new CompletableFuture<>();
        synchronized (stateLock) {
            if (!acceptingNew) {
                // Po zamknięciu NIE startujemy nowych insertów (nie dotykamy zamykanego executora HexCore).
                return CompletableFuture.completedFuture(-1L);
            }
            pending.add(tracked);   // rejestracja PRZED startem async, pod tym samym lockiem co migawka
        }
        // Usunięcie z pending dokładnie raz, gdy śledzony future się domknie (sukces/błąd/terminal).
        tracked.whenComplete((v, e) -> {
            synchronized (stateLock) {
                pending.remove(tracked);
            }
        });
        Runnable barrier = asyncSubmitBarrier;
        if (barrier != null) {
            try {
                barrier.run();
            } catch (Throwable ignored) {
                // bariera testowa nie może wpłynąć na ścieżkę produkcyjną
            }
        }
        submitInsert(b, tracked);
        return tracked;
    }

    /**
     * Zleca właściwy insert POZA lockiem i domyka {@code tracked} terminalnie. Synchroniczny błąd
     * zgłoszenia async (np. odrzucenie przez executor) jest łapany i zamieniany na terminalne {@code -1L} -
     * NIGDY nie leci do wołającego (transakcji gracza).
     */
    private void submitInsert(Builder b, CompletableFuture<Long> tracked) {
        long now = System.currentTimeMillis();
        CompletableFuture<Long> f;
        try {
            f = hexCore.async(() -> repo.insert(
                    now, b.actorUuid, b.actorName, b.action, b.market, b.itemKey,
                    b.listingId, b.orderId, b.claimId, b.amount, b.unitPrice, b.total,
                    b.result, b.reason, b.metadataJson));
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Nie udało się zlecić zapisu audytu, akcja=" + b.action, t);
            tracked.complete(-1L);
            return;
        }
        f.whenComplete((v, e) -> {
            if (e != null) {
                // Błąd audytu NIGDY nie zatrzymuje transakcji gracza - tylko log konsoli.
                logger.log(Level.WARNING, "Nie udało się zapisać wpisu audytu, akcja=" + b.action, e);
                tracked.complete(-1L);
            } else {
                tracked.complete(v == null ? -1L : v);
            }
        });
    }

    /**
     * Ograniczone (bounded) oczekiwanie na trwające inserty audytu przy reloadzie/wyłączeniu.
     * {@code stopAcceptingNew=true} przy DISABLE (kolejne {@code log()} nic nie wstawiają). Migawka
     * pending jest robiona pod {@link #stateLock} atomowo ze stop-accepting - dlatego widzi KAŻDY
     * wcześniej zaakceptowany wpis. NIGDY nie zamyka puli/executora HexCore. Przekroczenie limitu ->
     * tylko ostrzeżenie, bez blokowania serwera.
     */
    public void awaitPending(long timeoutMs, boolean stopAcceptingNew) {
        List<CompletableFuture<Long>> snapshot;
        synchronized (stateLock) {
            if (stopAcceptingNew) {
                acceptingNew = false;
            }
            snapshot = new ArrayList<>(pending);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0]))
                    .get(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            logger.warning("Audyt: część trwających wpisów nie została domknięta w limicie czasu.");
        }
    }

    /** Do testów: liczba trwających insertów audytu. */
    int pendingCount() {
        synchronized (stateLock) {
            return pending.size();
        }
    }

    boolean isShutdown() {
        synchronized (stateLock) {
            return !acceptingNew;
        }
    }

    public CompletableFuture<List<AuditEntry>> query(String kind, String value, int limit) {
        return hexCore.async(() -> {
            switch (kind.toLowerCase()) {
                case "player":
                case "actor": {
                    try {
                        UUID uuid = UUID.fromString(value);
                        return repo.findByActor(uuid, limit);
                    } catch (IllegalArgumentException ex) {
                        return List.<AuditEntry>of();
                    }
                }
                case "item":
                    return repo.findByItem(value.toLowerCase(), limit);
                case "listing":
                    try {
                        return repo.findByListing(Long.parseLong(value), limit);
                    } catch (NumberFormatException ex) {
                        return List.<AuditEntry>of();
                    }
                case "order":
                    try {
                        return repo.findByOrder(Long.parseLong(value), limit);
                    } catch (NumberFormatException ex) {
                        return List.<AuditEntry>of();
                    }
                case "market":
                    return repo.findByMarket(value.toUpperCase(), limit);
                default:
                    return List.<AuditEntry>of();
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Nie udało się wykonać zapytania do audytu", ex);
            return List.of();
        });
    }

    public CompletableFuture<List<String>> queryFormatted(String kind, String value, int limit) {
        return query(kind, value, limit).thenApply(entries -> {
            List<String> lines = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            for (AuditEntry e : entries) {
                StringBuilder fields = new StringBuilder();
                appendField(fields, "auction.admin-audit-field-market",
                        localizeToken("auction.admin-audit-market.", e.market()));
                if (e.actorName() != null) appendField(fields, "auction.admin-audit-field-actor", e.actorName());
                if (e.itemKey() != null) appendField(fields, "auction.admin-audit-field-item", e.itemKey());
                if (e.amount() != null) appendField(fields, "auction.admin-audit-field-amount",
                        String.valueOf(e.amount()));
                if (e.total() != null) appendField(fields, "auction.admin-audit-field-total",
                        e.total().toPlainString());
                if (e.orderId() != null) appendField(fields, "auction.admin-audit-field-order",
                        String.valueOf(e.orderId()));
                if (e.listingId() != null) appendField(fields, "auction.admin-audit-field-listing",
                        String.valueOf(e.listingId()));
                String resultLabel = switch (e.result() == null ? "" : e.result()) {
                    case "OK" -> messages.raw("auction.admin-audit-result-ok", null);
                    case "FAILED" -> messages.raw("auction.admin-audit-result-failed", null);
                    case "ROLLBACK" -> messages.raw("auction.admin-audit-result-rollback", null);
                    case "REFUND_PENDING" -> messages.raw("auction.admin-audit-result-refund-pending", null);
                    default -> localizeToken("auction.admin-audit-result.", e.result());
                };
                lines.add(messages.raw("auction.admin-audit-line",
                        MessageFactory.placeholders(
                                "date", fmt.format(Instant.ofEpochMilli(e.createdAt())),
                                "action", localizeToken("auction.admin-audit-action.", e.action()),
                                "fields", fields.toString().trim(),
                                "result", resultLabel)));
            }
            return lines;
        });
    }

    private void appendField(StringBuilder sb, String path, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append(messages.raw(path, MessageFactory.placeholders("value", value)));
        sb.append(' ');
    }

    /**
     * Lokalizuje techniczny token DB (akcja/rynek) na polską etykietę. Nieznany token -> bezpieczny
     * polski opis (z tokenem w nawiasie do diagnostyki), NIGDY surowy enum bez kontekstu.
     */
    String localizeToken(String keyPrefix, String token) {
        if (token == null || token.isEmpty()) {
            return messages.raw("auction.admin-audit-unknown", null);
        }
        String key = keyPrefix + token.toLowerCase(java.util.Locale.ROOT);
        if (messages.has(key)) {
            return messages.raw(key, null);
        }
        return messages.raw("auction.admin-audit-unknown-token",
                MessageFactory.placeholders("token", token));
    }
}

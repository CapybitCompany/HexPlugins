package hex.auctionbazaar.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Produkcyjna implementacja {@link SignPromptTransport} dla Paper 1.21.11.
 *
 * Tabliczka jest umieszczana jako tymczasowy blok (z applyPhysics=false, więc nie wywołuje aktualizacji
 * sąsiadów), oznaczana UNIKALNYM markerem sesji w PDC BlockEntity, a edytor otwierany przez
 * {@link Player#openSign(Sign, Side)} (natywne Paper API - bez pakietów NMS).
 *
 * <p>Przywrócenie bloku jest idempotentne i następuje WYŁĄCZNIE gdy w miejscu wciąż stoi NASZA tabliczka:
 * materiał = OAK_SIGN ORAZ marker sesji w PDC dokładnie pasuje do uchwytu. Dzięki temu obca/pluginowa
 * tabliczka OAK_SIGN w tym samym miejscu NIGDY nie zostaje nadpisana (punkt #9). Po restore cały blok
 * jest podmieniany, więc marker znika w całości.</p>
 */
public final class BukkitSignPromptTransport implements SignPromptTransport {

    private static final Material SIGN_MATERIAL = Material.OAK_SIGN;

    private final Plugin plugin;
    private final Logger logger;
    private final NamespacedKey sessionKey;

    public BukkitSignPromptTransport(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.sessionKey = new NamespacedKey(plugin, "sign_prompt_session");
    }

    @Override
    public void closeUi(Player player) {
        try {
            player.closeInventory();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Optional<SignHandle> prepareSign(Player player, List<String> lines) {
        Optional<Location> locOpt = SignLocationPicker.pick(player);
        if (locOpt.isEmpty()) {
            return Optional.empty();
        }
        Location loc = locOpt.get();
        Block block = loc.getBlock();
        Material originalMaterial = block.getType();
        BlockData originalData = block.getBlockData().clone();
        UUID sessionId = UUID.randomUUID();
        // Stufowy stan przygotowania (punkt #2): gdy blok świata został już zmieniony przez nas, KAŻDA
        // częściowa awaria musi bezpiecznie przywrócić oryginał - bez ghost-tabliczki i bez nadpisania
        // cudzej ingerencji. Marker utrwalamy NAJPIERW, więc dalsze etapy są już marker-bezpieczne.
        boolean blockChanged = false;
        boolean markerPersisted = false;
        try {
            blockChanged = true;                       // zamierzamy zmienić blok świata (przed setType)
            block.setType(SIGN_MATERIAL, false);
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                throw new IllegalStateException("oczekiwano stanu tabliczki (Sign), otrzymano "
                        + state.getClass().getName());
            }
            // Unikalny marker sesji - odróżnia NASZĄ tymczasową tabliczkę od dowolnej innej OAK_SIGN.
            sign.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, sessionId.toString());
            // Utrwalamy MARKER osobnym update PRZED zapisem linii: dzięki temu każda późniejsza awaria
            // (linie / kolejny update) jest już marker-bezpieczna i restore rozpozna naszą tabliczkę.
            sign.update(true, false);
            markerPersisted = true;
            for (int i = 0; i < 4; i++) {
                String text = i < lines.size() ? lines.get(i) : "";
                sign.line(i, Component.text(text == null ? "" : text));
            }
            sign.update(true, false);
            return Optional.of(new BukkitSignHandle(loc, originalMaterial, originalData, sessionId));
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Wprowadzanie przez tabliczkę: nie udało się przygotować tabliczki dla "
                    + player.getName() + " - przełączam na czat: " + t.getMessage());
            restoreAfterPrepareFailure(loc, originalMaterial, originalData, sessionId,
                    blockChanged, markerPersisted);
            return Optional.empty();
        }
    }

    @Override
    public OpenResult openEditor(Player player, SignHandle handle) {
        try {
            Block block = handle.location().getBlock();
            if (block.getState() instanceof Sign sign) {
                // OPENED oznacza tylko, że Paper przyjął wywołanie - NIE że klient wyświetlił okno.
                // Dlatego równoległy awaryjny prompt na czacie i tak zostaje uzbrojony (patrz SignPrompt).
                player.openSign(sign, Side.FRONT);
                return OpenResult.OPENED;
            }
            // Blok nie jest już tabliczką (obca zmiana) - nie otwieramy; wołający przejdzie na czat.
            return OpenResult.FAILED;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Wprowadzanie przez tabliczkę: nie udało się otworzyć edytora dla "
                    + player.getName() + ": " + t.getMessage());
            return OpenResult.FAILED;
        }
    }

    @Override
    public Cancellable runLater(Runnable task, long ticks) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, ticks));
        return () -> {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        };
    }

    @Override
    public void runMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void sendMessage(Player player, Component message) {
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    /**
     * Czysta decyzja o przywróceniu (testowalna bez świata): przywracamy TYLKO gdy w miejscu wciąż stoi
     * NASZA tabliczka - materiał OAK_SIGN ORAZ marker sesji dokładnie pasuje. Obca OAK_SIGN (brak markera
     * lub inny marker) oraz zmieniony materiał -> NIE przywracamy (nie nadpisujemy cudzej ingerencji).
     */
    static boolean shouldRestore(Material current, String currentMarker, String expectedSession) {
        return current == SIGN_MATERIAL && expectedSession != null && expectedSession.equals(currentMarker);
    }

    /**
     * Czysta decyzja przywrócenia po CZĘŚCIOWEJ awarii {@link #prepareSign} (punkt #2), rozłączna dla
     * każdego etapu:
     *  - blok jeszcze nietknięty ({@code !blockChanged}) -> nie ma czego przywracać (false);
     *  - marker już utrwalony ({@code markerPersisted}) -> przywracamy marker-bezpiecznie
     *    ({@link #shouldRestore}): tylko NASZA OAK_SIGN tej sesji; obca tabliczka/blok NIE są nadpisywane;
     *  - blok zmieniony na OAK_SIGN, ale marker jeszcze NIE utrwalony (awaria w tym samym, synchronicznym
     *    ticku - nic obcego nie mogło się wcisnąć) -> przywracamy wtedy i tylko wtedy, gdy w miejscu wciąż
     *    stoi OAK_SIGN (to nasz świeżo postawiony blok, jeszcze bez markera).
     */
    static boolean shouldRestoreAfterPrepareFailure(boolean blockChanged, boolean markerPersisted,
                                                    Material current, String currentMarker, String session) {
        if (!blockChanged) {
            return false;
        }
        if (markerPersisted) {
            return shouldRestore(current, currentMarker, session);
        }
        return current == SIGN_MATERIAL;
    }

    /** Odczyt markera sesji z PDC bloku (null gdy to nie tabliczka lub brak markera). Nigdy nie rzuca. */
    private String readMarker(Block block) {
        try {
            if (block.getState() instanceof Sign sign) {
                return sign.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Przywrócenie po częściowej awarii prepare (patrz {@link #shouldRestoreAfterPrepareFailure}). */
    private void restoreAfterPrepareFailure(Location loc, Material material, BlockData data, UUID sessionId,
                                            boolean blockChanged, boolean markerPersisted) {
        try {
            Block block = loc.getBlock();
            if (shouldRestoreAfterPrepareFailure(blockChanged, markerPersisted, block.getType(),
                    readMarker(block), sessionId.toString())) {
                block.setType(material, false);
                block.setBlockData(data, false);
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Wprowadzanie przez tabliczkę: nie udało się przywrócić bloku po "
                    + "częściowej awarii przygotowania w " + loc, t);
        }
    }

    private void restoreBlock(Location loc, Material material, BlockData data, UUID sessionId) {
        try {
            Block block = loc.getBlock();
            if (!shouldRestore(block.getType(), readMarker(block), sessionId.toString())) {
                // Obca/pluginowa OAK_SIGN albo zmieniony blok - NIE nadpisujemy (unikamy zniszczenia
                // cudzej ingerencji i przywrócenia „znikąd").
                return;
            }
            // Podmiana całego bloku usuwa też marker sesji z PDC.
            block.setType(material, false);
            block.setBlockData(data, false);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Wprowadzanie przez tabliczkę: nie udało się przywrócić bloku w "
                    + loc, t);
        }
    }

    /** Uchwyt przywracający blok - idempotentny (przywrócenie dokładnie raz) i związany z sesją. */
    private final class BukkitSignHandle implements SignHandle {
        private final Location location;
        private final Material originalMaterial;
        private final BlockData originalData;
        private final UUID sessionId;
        private final AtomicBoolean restored = new AtomicBoolean(false);

        BukkitSignHandle(Location location, Material originalMaterial, BlockData originalData, UUID sessionId) {
            this.location = location;
            this.originalMaterial = originalMaterial;
            this.originalData = originalData;
            this.sessionId = sessionId;
        }

        @Override
        public Location location() {
            return location;
        }

        @Override
        public void restore() {
            if (restored.compareAndSet(false, true)) {
                restoreBlock(location, originalMaterial, originalData, sessionId);
            }
        }
    }
}

package hexnpc.service;

import hexnpc.config.HexNpcConfig;
import hexnpc.model.LookAtSettings;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.render.NpcRenderer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Laesst NPCs mit aktiviertem Look-At nahe Spieler anschauen. Rein packet-seitig ueber
 * {@link NpcRenderer#lookAt}/{@link NpcRenderer#resetLook} — die gespeicherte NPC-Location
 * (yaw/pitch) wird NIE veraendert.
 *
 * <p>Ein einziger Task tickt jede Runde; pro NPC begrenzt ein Countdown die Arbeit auf das
 * konfigurierte {@code interval-ticks}. Schwere Arbeit (naechsten Spieler suchen, Pakete)
 * faellt nur an, wenn Look-At aktiv ist und das Intervall erreicht wird. Distanzen werden
 * quadriert verglichen.
 */
public final class NpcLookAtService {

    private final Plugin plugin;
    private final NpcService npcService;
    private final NpcRenderer renderer;
    private final Supplier<HexNpcConfig> configSupplier;

    /** Aktuelles Blickziel pro NPC (fuer Reset-Erkennung bei Zielwechsel/-verlust). */
    private final Map<NpcId, UUID> currentTarget = new HashMap<>();
    /** Verbleibende Ticks bis zum naechsten Update pro NPC. */
    private final Map<NpcId, Integer> countdown = new HashMap<>();
    private BukkitTask task;

    public NpcLookAtService(Plugin plugin,
                            NpcService npcService,
                            NpcRenderer renderer,
                            Supplier<HexNpcConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.npcService = Objects.requireNonNull(npcService, "npcService");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        currentTarget.clear();
        countdown.clear();
    }

    private void tick() {
        HexNpcConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return;
        }
        for (NpcDefinition npc : npcService.list()) {
            LookAtSettings look = npc.lookAt();
            NpcId id = npc.id();
            if (!look.enabled()) {
                // Deaktiviert: einmalig zuruecksetzen, falls zuletzt ein Ziel aktiv war.
                if (currentTarget.remove(id) != null && look.resetWhenEmpty()) {
                    renderer.resetLook(id);
                }
                countdown.remove(id);
                continue;
            }
            int remaining = countdown.getOrDefault(id, 0);
            if (remaining > 0) {
                countdown.put(id, remaining - 1);
                continue;
            }
            countdown.put(id, Math.max(1, look.intervalTicks()) - 1);
            updateNpc(config, npc, look);
        }
    }

    /**
     * Test-Seam: fuehrt fuer alle NPCs genau ein Update aus, ohne den Countdown-Throttle.
     */
    void scanOnce() {
        HexNpcConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return;
        }
        for (NpcDefinition npc : npcService.list()) {
            LookAtSettings look = npc.lookAt();
            if (!look.enabled()) {
                if (currentTarget.remove(npc.id()) != null && look.resetWhenEmpty()) {
                    renderer.resetLook(npc.id());
                }
                continue;
            }
            updateNpc(config, npc, look);
        }
    }

    private void updateNpc(HexNpcConfig config, NpcDefinition npc, LookAtSettings look) {
        NpcId id = npc.id();
        Location npcLoc = npc.location().toBukkit();
        if (npcLoc == null || npcLoc.getWorld() == null) {
            return;
        }
        double range = look.effectiveRange(config.proximity().defaultRadius());
        Player target = nearestPlayer(npcLoc, range * range);
        if (target != null) {
            Location eye = target.getEyeLocation();
            float[] yawPitch = LookAtCalculator.yawPitch(
                    npcLoc.getX(), npcLoc.getY() + LookAtCalculator.DEFAULT_EYE_HEIGHT, npcLoc.getZ(),
                    eye.getX(), eye.getY(), eye.getZ());
            renderer.lookAt(id, yawPitch[0], yawPitch[1]);
            currentTarget.put(id, target.getUniqueId());
        } else if (currentTarget.remove(id) != null && look.resetWhenEmpty()) {
            // Kein Ziel mehr in Range -> zurueck zur gespeicherten Rotation.
            renderer.resetLook(id);
        }
    }

    /** Naechster Spieler zur NPC-Position innerhalb {@code rangeSquared}, sonst {@code null}. */
    private Player nearestPlayer(Location npcLoc, double rangeSquared) {
        World world = npcLoc.getWorld();
        if (world == null) {
            return null;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : world.getPlayers()) {
            double distSq = player.getLocation().distanceSquared(npcLoc);
            if (distSq <= rangeSquared && distSq < best) {
                best = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    /** Test-Seam: aktuelles Blickziel eines NPCs (oder {@code null}). */
    UUID currentTarget(NpcId id) {
        return currentTarget.get(id);
    }
}

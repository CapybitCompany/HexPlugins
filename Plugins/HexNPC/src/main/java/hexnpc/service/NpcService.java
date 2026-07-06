package hexnpc.service;

import hexnpc.config.HexNpcConfig;
import hexnpc.model.Dialogue;
import hexnpc.model.DialogueLine;
import hexnpc.model.InteractionSettings;
import hexnpc.model.InteractionTrigger;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import hexnpc.render.NpcRenderer;
import hexnpc.storage.NpcStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class NpcService {

    private final NpcStorage storage;
    private final NpcRenderer renderer;
    private final Supplier<HexNpcConfig> configSupplier;
    private final Logger logger;

    public NpcService(NpcStorage storage,
                      NpcRenderer renderer,
                      Supplier<HexNpcConfig> configSupplier,
                      Logger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    private boolean renderingEnabled() {
        HexNpcConfig config = configSupplier.get();
        return config != null && config.enabled();
    }

    public void loadAndSpawnAll() throws Exception {
        storage.load();
        if (!renderingEnabled()) {
            return;
        }
        for (NpcDefinition def : storage.all()) {
            renderer.spawn(def);
        }
    }

    /** Used when enabled=false: load NPCs into memory but do not render. */
    public void loadOnly() throws Exception {
        storage.load();
    }

    public void spawnAll() {
        if (!renderingEnabled()) {
            return;
        }
        for (NpcDefinition def : storage.all()) {
            renderer.spawn(def);
        }
    }

    public void despawnAll() {
        for (NpcDefinition def : storage.all()) {
            renderer.despawn(def.id());
        }
    }

    public Collection<NpcDefinition> list() {
        return storage.all();
    }

    public Optional<NpcDefinition> find(NpcId id) {
        return storage.find(id);
    }

    public NpcDefinition create(NpcId id, NpcLocation location) throws Exception {
        if (storage.find(id).isPresent()) {
            throw new IllegalStateException("NPC already exists: " + id);
        }
        NpcDefinition def = new NpcDefinition(
                id,
                NpcSkin.ofName(id.value()),
                location,
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty()
        );
        storage.save(def);
        if (renderingEnabled()) {
            renderer.spawn(def);
        }
        return def;
    }

    public boolean remove(NpcId id) throws Exception {
        boolean deleted = storage.delete(id);
        if (deleted) {
            renderer.despawn(id);
        }
        return deleted;
    }

    public Optional<NpcDefinition> move(NpcId id, NpcLocation newLocation) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withLocation(newLocation);
        storage.save(updated);
        if (renderingEnabled()) {
            renderer.move(updated);
        }
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> rotate(NpcId id, float yaw, float pitch) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withLocation(current.get().location().withRotation(yaw, pitch));
        storage.save(updated);
        if (renderingEnabled()) {
            renderer.rotate(updated);
        }
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> setSkin(NpcId id, NpcSkin skin) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withSkin(skin);
        storage.save(updated);
        // Skin changes require a full respawn at the renderer level.
        if (renderingEnabled()) {
            renderer.despawn(id);
            renderer.spawn(updated);
        }
        return Optional.of(updated);
    }

    /**
     * Setzt den sichtbaren Nickname (Legacy-Farben erlaubt). {@code null}/leer
     * setzt zurueck auf den Standard (NPC-Id). Aendert den Skin NICHT.
     */
    public Optional<NpcDefinition> setDisplayName(NpcId id, String displayName) throws Exception {
        return updateAppearance(id, appearance -> appearance.withDisplayName(displayName));
    }

    public Optional<NpcDefinition> setGlow(NpcId id, boolean glow) throws Exception {
        return updateAppearance(id, appearance -> appearance.withGlow(glow));
    }

    public Optional<NpcDefinition> setPose(NpcId id, NpcPose pose) throws Exception {
        return updateAppearance(id, appearance -> appearance.withPose(pose));
    }

    private Optional<NpcDefinition> updateAppearance(
            NpcId id, java.util.function.UnaryOperator<NpcAppearance> mutator) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withAppearance(mutator.apply(current.get().appearance()));
        storage.save(updated);
        // Appearance (Name/Glow/Pose) wird beim Client per Respawn zuverlaessig neu aufgebaut.
        if (renderingEnabled()) {
            renderer.despawn(id);
            renderer.spawn(updated);
        }
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> setInteraction(NpcId id, InteractionSettings interaction) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withInteraction(interaction);
        storage.save(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> addDialogueLine(NpcId id, DialogueLine line) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Dialogue old = current.get().dialogue();
        List<DialogueLine> lines = new ArrayList<>(old.lines());
        lines.add(line);
        NpcDefinition updated = current.get().withDialogue(new Dialogue(lines, old.cooldownTicks()));
        storage.save(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> clearDialogue(NpcId id) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withDialogue(Dialogue.empty());
        storage.save(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> setDialogueCooldown(NpcId id, int cooldownTicks) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        Dialogue old = current.get().dialogue();
        NpcDefinition updated = current.get().withDialogue(new Dialogue(old.lines(), cooldownTicks));
        storage.save(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> addAction(NpcId id, InteractionTrigger trigger, NpcAction action) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcActions actions = current.get().actions();
        List<NpcAction> existing = actions.forTrigger(trigger);
        List<NpcAction> next = new ArrayList<>(existing);
        next.add(action);
        NpcActions updated = switch (trigger) {
            case CLICK -> actions.withOnClick(next);
            case PROXIMITY -> actions.withOnProximity(next);
        };
        NpcDefinition def = current.get().withActions(updated);
        storage.save(def);
        return Optional.of(def);
    }

    public Optional<NpcDefinition> clearActions(NpcId id, InteractionTrigger trigger) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcActions actions = current.get().actions();
        NpcActions updated = switch (trigger) {
            case CLICK -> actions.withOnClick(List.of());
            case PROXIMITY -> actions.withOnProximity(List.of());
        };
        NpcDefinition def = current.get().withActions(updated);
        storage.save(def);
        return Optional.of(def);
    }

    public Optional<NpcDefinition> clearAllActions(NpcId id) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition def = current.get().withActions(NpcActions.empty());
        storage.save(def);
        return Optional.of(def);
    }

    public Logger logger() {
        return logger;
    }
}

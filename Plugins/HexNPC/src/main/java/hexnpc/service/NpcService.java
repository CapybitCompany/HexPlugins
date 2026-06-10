package hexnpc.service;

import hexnpc.model.Dialogue;
import hexnpc.model.DialogueLine;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import hexnpc.render.NpcRenderer;
import hexnpc.storage.NpcStorage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

public final class NpcService {

    private final NpcStorage storage;
    private final NpcRenderer renderer;
    private final Logger logger;

    public NpcService(NpcStorage storage, NpcRenderer renderer, Logger logger) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void loadAndSpawnAll() throws Exception {
        storage.load();
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
                List.of()
        );
        storage.save(def);
        renderer.spawn(def);
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
        renderer.move(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> rotate(NpcId id, float yaw, float pitch) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withLocation(current.get().location().withRotation(yaw, pitch));
        storage.save(updated);
        renderer.rotate(updated);
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
        renderer.despawn(id);
        renderer.spawn(updated);
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

    public Optional<NpcDefinition> addAction(NpcId id, NpcAction action) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<NpcAction> actions = new ArrayList<>(current.get().actions());
        actions.add(action);
        NpcDefinition updated = current.get().withActions(actions);
        storage.save(updated);
        return Optional.of(updated);
    }

    public Optional<NpcDefinition> clearActions(NpcId id) throws Exception {
        Optional<NpcDefinition> current = storage.find(id);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        NpcDefinition updated = current.get().withActions(List.of());
        storage.save(updated);
        return Optional.of(updated);
    }

    public Logger logger() {
        return logger;
    }
}

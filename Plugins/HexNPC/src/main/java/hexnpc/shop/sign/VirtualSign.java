package hexnpc.shop.sign;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;
import com.github.retrooper.packetevents.protocol.world.blockentity.BlockEntityTypes;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockEntityData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenSignEditor;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Izolacja wywołań PacketEvents dla wirtualnego edytora tabliczki. Ta klasa
 * ładuje typy PacketEvents dopiero przy pierwszym użyciu — wolno jej dotykać
 * wyłącznie, gdy PacketEvents jest dostępny.
 *
 * <p>Aby klient 1.20+/1.21 faktycznie otworzył edytor, wysyłamy trzy pakiety:
 * <ol>
 *   <li>zmianę bloku na tabliczkę (stan bloku po stronie klienta),</li>
 *   <li>dane block-entity tabliczki z pustymi liniami (bez tego edytor się
 *       nie otwiera — najczęstsza przyczyna „nic się nie dzieje"),</li>
 *   <li>OpenSignEditor dla przedniej strony tabliczki.</li>
 * </ol>
 * Wszystkie trzy etapy są wymagane do sukcesu. Metoda zwraca etap, do którego
 * doszedł wysył — dzięki temu wywołujący wie, że przy częściowym błędzie
 * istnieje ghost-blok do przywrócenia i że tryb sign NIE jest aktywny.
 * Żaden realny blok w świecie nie jest zmieniany.
 */
public final class VirtualSign {

    private static volatile int cachedSignId = Integer.MIN_VALUE;

    private VirtualSign() {
    }

    /** Globalny id stanu bloku tabliczki (cache'owany). -1 jeśli nieznany. */
    public static int signBlockId() {
        int cached = cachedSignId;
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        int resolved;
        try {
            WrappedBlockState state = WrappedBlockState.getByString("minecraft:oak_sign");
            resolved = state == null ? -1 : state.getGlobalId();
        } catch (Throwable t) {
            resolved = -1;
        }
        cachedSignId = resolved;
        return resolved;
    }

    /**
     * Wysyła wirtualną tabliczkę + block-entity + OpenSignEditor. Zwraca etap,
     * do którego doszedł wysył — {@code OPEN_EDITOR} tylko przy pełnym sukcesie
     * wszystkich trzech pakietów. Błąd na etapie block-entity NIE jest
     * traktowany jako sukces. Diagnostyka trafia do logu tylko przy
     * {@code debug=true}.
     */
    public static SignTransport.OpenResult openEditor(Player player, int x, int y, int z,
                                                      Logger logger, boolean debug) {
        int signId = signBlockId();
        if (signId < 0) {
            if (debug) {
                warn(logger, "nie udało się ustalić id bloku tabliczki");
            }
            return SignTransport.OpenResult.none("brak id bloku tabliczki");
        }
        SignTransport.Stage reached = SignTransport.Stage.NONE;
        try {
            Vector3i pos = new Vector3i(x, y, z);
            var pm = PacketEvents.getAPI().getPlayerManager();
            pm.sendPacket(player, new WrapperPlayServerBlockChange(pos, signId));
            reached = SignTransport.Stage.BLOCK_CHANGE;
            // Block-entity z pustym tekstem — bez tego edytor na 1.20+ się nie
            // otwiera. Błąd tutaj = etap częściowy, NIE sukces.
            pm.sendPacket(player, new WrapperPlayServerBlockEntityData(pos, BlockEntityTypes.SIGN, emptySignNbt()));
            reached = SignTransport.Stage.BLOCK_ENTITY;
            pm.sendPacket(player, new WrapperPlayServerOpenSignEditor(pos, true));
            reached = SignTransport.Stage.OPEN_EDITOR;
            return new SignTransport.OpenResult(SignTransport.Stage.OPEN_EDITOR, null);
        } catch (Throwable t) {
            if (debug) {
                warn(logger, "błąd wysyłki na etapie " + reached + ": " + t.getMessage());
            }
            return new SignTransport.OpenResult(reached, t.getMessage());
        }
    }

    private static NBTCompound emptySignNbt() {
        NBTCompound nbt = new NBTCompound();
        nbt.setTag("is_waxed", new NBTByte((byte) 0));
        nbt.setTag("front_text", emptySide());
        nbt.setTag("back_text", emptySide());
        return nbt;
    }

    private static NBTCompound emptySide() {
        NBTCompound side = new NBTCompound();
        NBTList<NBTString> messages = NBTList.createStringList();
        for (int i = 0; i < 4; i++) {
            messages.addTag(new NBTString("\"\""));
        }
        side.setTag("messages", messages);
        side.setTag("color", new NBTString("black"));
        side.setTag("has_glowing_text", new NBTByte((byte) 0));
        return side;
    }

    private static void warn(Logger logger, String message) {
        if (logger != null) {
            logger.warning("HexNPC: wirtualna tabliczka — " + message);
        }
    }
}

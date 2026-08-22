package hexnpc.shop.sign;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;

import java.util.Objects;
import java.util.UUID;

/**
 * Nasłuchuje przychodzącego pakietu UPDATE_SIGN (potwierdzenie edycji
 * tabliczki) i przekazuje surowe linie do {@link SignInputSink}.
 * Instancjonowana wyłącznie, gdy PacketEvents jest dostępny.
 */
public final class SignUpdatePacketListener extends PacketListenerAbstract {

    private final SignInputSink sink;

    public SignUpdatePacketListener(SignInputSink sink) {
        super(PacketListenerPriority.NORMAL);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.UPDATE_SIGN) {
            return;
        }
        UUID uuid = event.getUser() == null ? null : event.getUser().getUUID();
        if (uuid == null) {
            return;
        }
        WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
        Vector3i pos = wrapper.getBlockPosition();
        String[] lines = wrapper.getTextLines();
        int x = pos == null ? 0 : pos.getX();
        int y = pos == null ? 0 : pos.getY();
        int z = pos == null ? 0 : pos.getZ();
        boolean consumed = sink.onSignUpdate(uuid, x, y, z, lines);
        if (consumed) {
            // Nasza wirtualna tabliczka — nie pozwól serwerowi przetwarzać
            // pakietu (brak realnego bloku-tabliczki na tej pozycji).
            event.setCancelled(true);
        }
    }
}

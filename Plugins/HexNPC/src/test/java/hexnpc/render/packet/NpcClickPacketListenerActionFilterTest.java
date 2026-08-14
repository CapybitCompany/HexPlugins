package hexnpc.render.packet;

import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcClickPacketListenerActionFilterTest {

    @Test
    void interactIsAccepted() {
        assertTrue(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT),
                "INTERACT must fire the CLICK trigger");
    }

    @Test
    void offHandInteractIsRejectedToAvoidDoubleFireButSuppressesItemUse() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT, InteractionHand.OFF_HAND),
                "OFF_HAND INTERACT would double-fire the trigger");
        assertTrue(NpcClickPacketListener.isNpcRightClick(InteractAction.INTERACT, InteractionHand.OFF_HAND),
                "OFF_HAND right-click still suppresses vanilla item use");
    }

    @Test
    void interactAtIsRejectedToAvoidDoubleFireButSuppressesItemUse() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT_AT),
                "INTERACT_AT would double-fire the trigger");
        assertTrue(NpcClickPacketListener.isNpcRightClick(InteractAction.INTERACT_AT, InteractionHand.MAIN_HAND),
                "INTERACT_AT also belongs to the same NPC right-click");
    }

    @Test
    void attackIsRejected() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.ATTACK),
                "Left-click must not fire the CLICK trigger");
        assertFalse(NpcClickPacketListener.isNpcRightClick(InteractAction.ATTACK, null),
                "ATTACK must not suppress item use");
    }

    @Test
    void vanillaUseItemPacketsAreSuppressed() {
        assertTrue(NpcClickPacketListener.isUseItemPacket(PacketType.Play.Client.USE_ITEM));
        assertTrue(NpcClickPacketListener.isUseItemPacket(PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT));
        assertFalse(NpcClickPacketListener.isUseItemPacket(PacketType.Play.Client.INTERACT_ENTITY));
    }
}

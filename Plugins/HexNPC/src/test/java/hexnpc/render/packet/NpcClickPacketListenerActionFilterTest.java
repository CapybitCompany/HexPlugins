package hexnpc.render.packet;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stellt sicher, dass aus den drei InteractAction-Varianten von
 * WrapperPlayClientInteractEntity nur der echte Rechtsklick durchgelassen wird.
 * Hintergrund: ein Vanilla-Rechtsklick sendet INTERACT_AT + INTERACT, ein
 * Linksklick ATTACK.
 */
class NpcClickPacketListenerActionFilterTest {

    @Test
    void interactIsAccepted() {
        assertTrue(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT),
                "INTERACT muss als CLICK-Trigger akzeptiert werden");
    }

    @Test
    void offHandInteractIsRejectedToAvoidDoubleFire() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT, InteractionHand.OFF_HAND),
                "OFF_HAND INTERACT wuerde zusammen mit MAIN_HAND pro Klick doppelt feuern");
    }

    @Test
    void interactAtIsRejectedToAvoidDoubleFire() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.INTERACT_AT),
                "INTERACT_AT würde zusammen mit INTERACT pro Klick doppelt feuern");
    }

    @Test
    void attackIsRejected() {
        assertFalse(NpcClickPacketListener.shouldHandle(InteractAction.ATTACK),
                "Linksklick darf den CLICK-Trigger nicht auslösen");
    }
}

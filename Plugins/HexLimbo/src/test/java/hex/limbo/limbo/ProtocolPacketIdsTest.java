package hex.limbo.limbo;

import hex.limbo.limbo.server.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anchors the {@link Protocol.Packets} constants to the concrete numeric values from the
 * {@code minecraft-data} JSON for {@code pc/1.21.11/protocol.json}. This guards against the kind
 * of "tests check code constants against code constants" regression that previously hid wrong
 * packet ids – every assertion below uses a hard-coded integer.
 *
 * <p>HANDSHAKE / STATUS / LOGIN / CONFIGURATION ids are byte-for-byte identical to 1.21.4; only
 * the PLAY-state ids shifted between 1.21.4 and 1.21.11.
 */
class ProtocolPacketIdsTest {

    @Test
    void targetVersionIs1_21_11() {
        // Deliberate: HexLimbo speaks Minecraft 1.21.11 (protocol 774) NATIVELY so ViaVersion on
        // the proxy passes a 1.21.11 client straight through without translating the limbo's
        // registry data. A 1.21.4 backend behind ViaVersion cannot carry the registries the newer
        // client requires (cow_variant, pig_variant, …) and always disconnected at config→play.
        assertEquals(774, Protocol.MINECRAFT_PROTOCOL_VERSION);
        assertEquals("1.21.11", Protocol.MINECRAFT_VERSION_LABEL);
    }

    // ---- clientbound play (1.21.11) ----

    @Test
    void playLoginClientboundIs0x30() {
        assertEquals(0x30, Protocol.Packets.PLAY_LOGIN_OUT);
    }

    @Test
    void playerAbilitiesClientboundIs0x3E() {
        assertEquals(0x3E, Protocol.Packets.PLAY_PLAYER_ABILITIES_OUT);
    }

    @Test
    void setCenterChunkClientboundIs0x5C() {
        assertEquals(0x5C, Protocol.Packets.PLAY_SET_CENTER_CHUNK_OUT);
    }

    @Test
    void mapChunkClientboundIs0x2C() {
        assertEquals(0x2C, Protocol.Packets.PLAY_CHUNK_DATA_OUT);
    }

    @Test
    void syncPlayerPositionClientboundIs0x46() {
        assertEquals(0x46, Protocol.Packets.PLAY_SYNC_PLAYER_POSITION_OUT);
    }

    @Test
    void gameEventClientboundIs0x26() {
        assertEquals(0x26, Protocol.Packets.PLAY_GAME_EVENT_OUT);
    }

    @Test
    void keepAliveClientboundIs0x2B() {
        assertEquals(0x2B, Protocol.Packets.PLAY_KEEPALIVE_OUT);
    }

    @Test
    void actionBarClientboundIs0x55() {
        assertEquals(0x55, Protocol.Packets.PLAY_ACTIONBAR_OUT);
    }

    @Test
    void setDefaultSpawnPositionClientboundIs0x5F() {
        assertEquals(0x5F, Protocol.Packets.PLAY_SET_DEFAULT_SPAWN_POSITION_OUT);
    }

    @Test
    void disconnectClientboundIs0x20() {
        assertEquals(0x20, Protocol.Packets.PLAY_DISCONNECT_OUT);
    }

    // ---- serverbound play (1.21.11) ----

    @Test
    void keepAliveServerboundIs0x1B() {
        assertEquals(0x1B, Protocol.Packets.PLAY_KEEPALIVE_RESPONSE);
    }

    @Test
    void setPlayerPositionServerboundIs0x1D() {
        assertEquals(0x1D, Protocol.Packets.PLAY_PLAYER_POSITION);
    }

    @Test
    void setPlayerPositionAndRotationServerboundIs0x1E() {
        assertEquals(0x1E, Protocol.Packets.PLAY_PLAYER_POSITION_ROTATION);
    }

    @Test
    void setPlayerRotationServerboundIs0x1F() {
        assertEquals(0x1F, Protocol.Packets.PLAY_PLAYER_ROTATION);
    }

    // ---- login + configuration sanity (unchanged from 1.21.4) ----

    @Test
    void loginSuccessClientboundIs0x02() {
        assertEquals(0x02, Protocol.Packets.LOGIN_SUCCESS_OUT);
    }

    @Test
    void loginPluginRequestClientboundIs0x04() {
        assertEquals(0x04, Protocol.Packets.LOGIN_PLUGIN_REQUEST_OUT);
    }

    @Test
    void loginPluginResponseServerboundIs0x02() {
        assertEquals(0x02, Protocol.Packets.LOGIN_PLUGIN_RESPONSE);
    }

    @Test
    void loginAcknowledgedServerboundIs0x03() {
        assertEquals(0x03, Protocol.Packets.LOGIN_ACKNOWLEDGED);
    }

    @Test
    void configFinishClientboundIs0x03() {
        assertEquals(0x03, Protocol.Packets.CONFIG_FINISH_OUT);
    }

    @Test
    void configFinishAckServerboundIs0x03() {
        assertEquals(0x03, Protocol.Packets.CONFIG_FINISH_ACK);
    }

    @Test
    void selectKnownPacksClientboundIs0x0E() {
        assertEquals(0x0E, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_OUT);
    }

    @Test
    void selectKnownPacksServerboundIs0x07() {
        assertEquals(0x07, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE);
    }

    @Test
    void featureFlagsClientboundIs0x0C() {
        assertEquals(0x0C, Protocol.Packets.CONFIG_FEATURE_FLAGS_OUT);
    }

    @Test
    void updateTagsClientboundIs0x0D() {
        assertEquals(0x0D, Protocol.Packets.CONFIG_UPDATE_TAGS_OUT);
    }
}

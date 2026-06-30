package hex.limbo.limbo;

import hex.limbo.limbo.server.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Anchors the {@link Protocol.Packets} constants to the concrete numeric values from the
 * {@code minecraft-data} JSON for {@code pc/1.21.4/protocol.json}. This guards against the kind
 * of "tests check code constants against code constants" regression that previously hid wrong
 * packet ids – every assertion below uses a hard-coded integer.
 */
class ProtocolPacketIdsTest {

    @Test
    void targetVersionIs1_21_4() {
        assertEquals(769, Protocol.MINECRAFT_PROTOCOL_VERSION);
        assertEquals("1.21.4", Protocol.MINECRAFT_VERSION_LABEL);
    }

    // ---- clientbound play ----

    @Test
    void playLoginClientboundIs0x2C() {
        assertEquals(0x2C, Protocol.Packets.PLAY_LOGIN_OUT);
    }

    @Test
    void playerAbilitiesClientboundIs0x3A() {
        assertEquals(0x3A, Protocol.Packets.PLAY_PLAYER_ABILITIES_OUT);
    }

    @Test
    void setCenterChunkClientboundIs0x58() {
        assertEquals(0x58, Protocol.Packets.PLAY_SET_CENTER_CHUNK_OUT);
    }

    @Test
    void mapChunkClientboundIs0x28() {
        assertEquals(0x28, Protocol.Packets.PLAY_CHUNK_DATA_OUT);
    }

    @Test
    void syncPlayerPositionClientboundIs0x42() {
        assertEquals(0x42, Protocol.Packets.PLAY_SYNC_PLAYER_POSITION_OUT);
    }

    @Test
    void gameEventClientboundIs0x23() {
        assertEquals(0x23, Protocol.Packets.PLAY_GAME_EVENT_OUT);
    }

    @Test
    void keepAliveClientboundIs0x27() {
        assertEquals(0x27, Protocol.Packets.PLAY_KEEPALIVE_OUT);
    }

    @Test
    void actionBarClientboundIs0x51() {
        assertEquals(0x51, Protocol.Packets.PLAY_ACTIONBAR_OUT);
    }

    @Test
    void setDefaultSpawnPositionClientboundIs0x5B() {
        assertEquals(0x5B, Protocol.Packets.PLAY_SET_DEFAULT_SPAWN_POSITION_OUT);
    }

    // ---- serverbound play ----

    @Test
    void keepAliveServerboundIs0x1A() {
        assertEquals(0x1A, Protocol.Packets.PLAY_KEEPALIVE_RESPONSE);
    }

    @Test
    void setPlayerPositionServerboundIs0x1C() {
        assertEquals(0x1C, Protocol.Packets.PLAY_PLAYER_POSITION);
    }

    @Test
    void setPlayerPositionAndRotationServerboundIs0x1D() {
        assertEquals(0x1D, Protocol.Packets.PLAY_PLAYER_POSITION_ROTATION);
    }

    @Test
    void setPlayerRotationServerboundIs0x1E() {
        assertEquals(0x1E, Protocol.Packets.PLAY_PLAYER_ROTATION);
    }

    // ---- login + configuration sanity ----

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

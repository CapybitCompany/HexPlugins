package hex.limbo.premium;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangProfileParserTest {

    @Test
    void parsesStandardMojangResponse() {
        String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";
        MojangProfileParser.Profile profile = MojangProfileParser.parse(body);
        assertTrue(profile.id().isPresent());
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.id().get());
        assertEquals(Optional.of("Notch"), profile.name());
    }

    @Test
    void parsesResponseWithExtraFields() {
        String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"legacy\":true,\"demo\":false}";
        MojangProfileParser.Profile profile = MojangProfileParser.parse(body);
        assertTrue(profile.id().isPresent());
        assertEquals(Optional.of("Notch"), profile.name());
    }

    @Test
    void parsesResponseWithWhitespace() {
        String body = "{  \"id\"  :  \"069a79f444e94726a5befca90e38aaf5\"  ,  \"name\"  :  \"Notch\"  }";
        MojangProfileParser.Profile profile = MojangProfileParser.parse(body);
        assertTrue(profile.id().isPresent());
        assertEquals(Optional.of("Notch"), profile.name());
    }

    @Test
    void parsesResponseWithReversedFieldOrder() {
        String body = "{\"name\":\"Notch\",\"id\":\"069a79f444e94726a5befca90e38aaf5\"}";
        MojangProfileParser.Profile profile = MojangProfileParser.parse(body);
        assertTrue(profile.id().isPresent());
        assertEquals(Optional.of("Notch"), profile.name());
    }

    @Test
    void returnsEmptyForMalformedBody() {
        MojangProfileParser.Profile broken = MojangProfileParser.parse("not json at all");
        assertTrue(broken.id().isEmpty());
        assertTrue(broken.name().isEmpty());
    }

    @Test
    void returnsEmptyForNullBody() {
        MojangProfileParser.Profile result = MojangProfileParser.parse(null);
        assertTrue(result.id().isEmpty());
        assertTrue(result.name().isEmpty());
    }

    @Test
    void handlesEscapedNameQuotes() {
        String body = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Inv\\\"alid\"}";
        MojangProfileParser.Profile profile = MojangProfileParser.parse(body);
        assertEquals(Optional.of("Inv\"alid"), profile.name());
    }

    @Test
    void uuidConversion32CharsToDashed() {
        Optional<UUID> uuid = MojangProfileParser.toDashedUuid("069a79f444e94726a5befca90e38aaf5");
        assertTrue(uuid.isPresent());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", uuid.get().toString());
    }

    @Test
    void uuidConversionRejectsWrongLength() {
        assertTrue(MojangProfileParser.toDashedUuid("069a79f444e94726a5befca90e38aaf").isEmpty());
        assertTrue(MojangProfileParser.toDashedUuid("").isEmpty());
    }

    @Test
    void uuidConversionRejectsNonHex() {
        assertTrue(MojangProfileParser.toDashedUuid("069a79f444e94726a5befca90e38aazz").isEmpty());
    }

    @Test
    void uuidConversionAcceptsDashedInput() {
        Optional<UUID> uuid = MojangProfileParser.toDashedUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        assertTrue(uuid.isPresent());
    }
}

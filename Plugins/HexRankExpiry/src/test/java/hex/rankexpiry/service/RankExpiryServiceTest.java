package hex.rankexpiry.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankExpiryServiceTest {
    @Test
    void dayWordUsesSingularOnlyForOneDay() {
        assertEquals("dni", RankExpiryService.dayWord(0));
        assertEquals("dzień", RankExpiryService.dayWord(1));
        assertEquals("dni", RankExpiryService.dayWord(2));
        assertEquals("dni", RankExpiryService.dayWord(5));
    }
}

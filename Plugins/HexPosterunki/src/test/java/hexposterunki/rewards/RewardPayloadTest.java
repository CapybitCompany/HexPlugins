package hexposterunki.rewards;

import hexposterunki.config.RewardsConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frozen reward content must survive a round trip through the database column unchanged. */
class RewardPayloadTest {

    @Test
    void itemsComeFirstThenCommandsInAStableOrder() {
        RewardPayload payload = RewardPayload.from(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 3, "<gold>Trofeum</gold>", List.of("<gray>1.</gray>")),
                        new RewardsConfig.ItemReward("GOLD_INGOT", 8, null, List.of())),
                List.of("eco give <player> 500", "broadcast ok")));

        assertEquals(4, payload.size());
        assertEquals(RewardComponent.Type.ITEM, payload.components().get(0).type());
        assertEquals(RewardComponent.Type.ITEM, payload.components().get(1).type());
        assertEquals(RewardComponent.Type.COMMAND, payload.components().get(2).type());
        assertEquals(RewardComponent.Type.COMMAND, payload.components().get(3).type());
    }

    @Test
    void serializationRoundTripsEveryField() {
        RewardPayload original = RewardPayload.from(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND_BLOCK", 2, "<gold>Trofeum posterunku</gold>",
                        List.of("<gray>1. miejsce</gray>", "<gray>Fort Północny</gray>"))),
                List.of("eco give <player> 5000")));

        RewardPayload restored = RewardPayload.deserialize(original.serialize());

        assertEquals(original.size(), restored.size());
        RewardComponent item = restored.components().get(0);
        assertEquals("DIAMOND_BLOCK", item.value());
        assertEquals(2, item.amount());
        assertEquals("<gold>Trofeum posterunku</gold>", item.displayName());
        assertEquals(List.of("<gray>1. miejsce</gray>", "<gray>Fort Północny</gray>"), item.lore());
        assertEquals("eco give <player> 5000", restored.components().get(1).value());
        assertEquals(RewardComponent.Type.COMMAND, restored.components().get(1).type());
    }

    @Test
    void brokenOrEmptyPayloadDegradesToNothingRatherThanThrowing() {
        assertTrue(RewardPayload.deserialize(null).isEmpty());
        assertTrue(RewardPayload.deserialize("").isEmpty());
        assertTrue(RewardPayload.deserialize("::: nie yaml :::").isEmpty());
    }

    @Test
    void anEmptyConfiguredRewardProducesNoClaimContent() {
        assertTrue(RewardPayload.from(new RewardsConfig.PlaceReward(List.of(), List.of())).isEmpty());
    }
}

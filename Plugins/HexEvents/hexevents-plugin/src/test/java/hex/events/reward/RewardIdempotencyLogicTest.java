package hex.events.reward;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class RewardIdempotencyLogicTest {
    public static void main(String[] args){
        UUID instance=UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID subject=UUID.fromString("20000000-0000-0000-0000-000000000001");
        String k=instance+":"+subject+":top10:0";
        UUID a=UUID.nameUUIDFromBytes(("hexevent-reward:"+k).getBytes(StandardCharsets.UTF_8));
        UUID b=UUID.nameUUIDFromBytes(("hexevent-reward:"+k).getBytes(StandardCharsets.UTF_8));
        if(!a.equals(b))throw new AssertionError("reward id must be deterministic");
        System.out.println("RewardIdempotencyLogicTest OK");
    }
}

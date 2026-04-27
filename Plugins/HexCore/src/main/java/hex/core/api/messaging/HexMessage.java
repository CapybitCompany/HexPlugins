package hex.core.api.messaging;

public record HexMessage(String channel, String sender, HexMessageData data) {

    public static HexMessage of(String channel, String sender, HexMessageData data) {
        return new HexMessage(channel, sender, data);
    }

    public static HexMessage signal(String channel, String sender) {
        return new HexMessage(channel, sender, HexMessageData.EMPTY);
    }
}


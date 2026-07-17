package hex.vishopbroadcast.proxy.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;

public final class ProxyText {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private ProxyText() {
    }

    public static String render(String template, Map<String, String> values) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    public static Component component(String template, Map<String, String> values) {
        return MINI_MESSAGE.deserialize(render(template, values));
    }
}

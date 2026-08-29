package hexcustomitems.util;

import java.util.Map;

public final class PlaceholderUtil {

    private PlaceholderUtil() {
    }

    public static String apply(String input, Map<String, String> replacements) {
        String result = input;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }
}

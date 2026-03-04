package hex.ranking.util;

import java.util.concurrent.CompletionException;

public final class MessageUtil {

    private static final String PREFIX = "§8[§6HexRanking§8] §r";

    private MessageUtil() {
    }

    public static String info(String message) {
        return PREFIX + "§f" + message;
    }

    public static String success(String message) {
        return PREFIX + "§a" + message;
    }

    public static String error(String message) {
        return PREFIX + "§c" + message;
    }

    public static String causeMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor instanceof CompletionException completionException && completionException.getCause() != null) {
            cursor = completionException.getCause();
        }

        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return cursor.getClass().getSimpleName();
        }
        return message;
    }
}

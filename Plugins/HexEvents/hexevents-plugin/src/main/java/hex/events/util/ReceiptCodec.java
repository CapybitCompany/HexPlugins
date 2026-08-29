package hex.events.util;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReceiptCodec {
    private ReceiptCodec() { }
    public static String encode(Map<String, String> data) {
        if (data == null || data.isEmpty()) return "";
        return data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }
    public static Map<String, String> decode(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String token : raw.split("&")) {
            int idx = token.indexOf('=');
            String k = idx < 0 ? token : token.substring(0, idx);
            String v = idx < 0 ? "" : token.substring(idx + 1);
            out.put(dec(k), dec(v));
        }
        return out;
    }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }
    private static String dec(String v) { return URLDecoder.decode(v, StandardCharsets.UTF_8); }
}

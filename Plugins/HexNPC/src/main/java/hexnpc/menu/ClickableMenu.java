package hexnpc.menu;

import java.util.List;

public record ClickableMenu(int timeoutSeconds, List<String> lines, List<Option> options) {
    public ClickableMenu {
        timeoutSeconds = Math.max(1, timeoutSeconds);
        lines = lines == null ? List.of() : List.copyOf(lines);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public record Option(String text, String hover, List<String> response) {
        public Option {
            text = text == null ? "" : text;
            hover = hover == null ? "" : hover;
            response = response == null ? List.of() : List.copyOf(response);
        }
    }
}

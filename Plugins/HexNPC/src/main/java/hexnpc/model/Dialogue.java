package hexnpc.model;

import java.util.List;

public record Dialogue(
        List<DialogueLine> lines,
        int cooldownTicks
) {
    public Dialogue {
        lines = lines == null ? List.of() : List.copyOf(lines);
        cooldownTicks = Math.max(0, cooldownTicks);
    }

    public static Dialogue empty() {
        return new Dialogue(List.of(), 0);
    }

    public boolean hasLines() {
        return !lines.isEmpty();
    }
}

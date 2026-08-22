package hexnpc.model;

public record DialogueLine(
        String text,
        int delayTicks
) {
    public DialogueLine {
        text = text == null ? "" : text;
        delayTicks = Math.max(0, delayTicks);
    }
}

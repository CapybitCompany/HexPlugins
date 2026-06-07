package hex.sequence;

public enum SequenceExecutorType {
    CONSOLE,
    PLAYER;

    public static SequenceExecutorType parse(String raw) throws SequenceParseException {
        String normalized = raw.trim().replace("[", "").replace("]", "").toUpperCase();
        try {
            return SequenceExecutorType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new SequenceParseException("Nieznany executor: " + raw + ". Dostepne: [console], [player].");
        }
    }
}


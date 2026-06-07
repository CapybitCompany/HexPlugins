package hex.sequence;

public record SequenceEntry(
        SequenceExecutorType executorType,
        long delayTicks,
        String command,
        int lineIndex
) {
}


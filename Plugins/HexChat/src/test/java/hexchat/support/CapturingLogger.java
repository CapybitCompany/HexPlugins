package hexchat.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Logger testowy zbierający wszystkie zalogowane komunikaty, aby testy mogły
 * zweryfikować obecność (lub brak) ostrzeżeń bez zaśmiecania konsoli.
 */
public final class CapturingLogger {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final Logger logger;
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    public CapturingLogger() {
        this.logger = Logger.getLogger("hexchat-test-" + COUNTER.incrementAndGet());
        this.logger.setUseParentHandlers(false);
        this.logger.setLevel(Level.ALL);
        this.logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    public Logger logger() {
        return logger;
    }

    public List<LogRecord> records() {
        return records;
    }

    public boolean hasWarningContaining(String fragment) {
        return records.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .anyMatch(record -> record.getMessage() != null && record.getMessage().contains(fragment));
    }

    public long warningCount() {
        return records.stream().filter(record -> record.getLevel() == Level.WARNING).count();
    }
}

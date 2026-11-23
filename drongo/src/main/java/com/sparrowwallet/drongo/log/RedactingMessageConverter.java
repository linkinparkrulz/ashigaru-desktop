package com.sparrowwallet.drongo.log;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * The log message with {@link LogRedaction} applied, so that nothing reaches the log file (which people send with bug reports) unredacted,
 * whichever class logged it. Lines from the swap service also have their amounts redacted.
 */
public class RedactingMessageConverter extends MessageConverter {
    /** Loggers whose lines are about swaps, where an exact amount would find the lock on chain */
    static final String SWAP_LOGGERS = "com.sparrowwallet.sparrow.swap";

    @Override
    public String convert(ILoggingEvent event) {
        return redact(event.getLoggerName(), super.convert(event));
    }

    static String redact(String loggerName, String text) {
        return isSwapLogger(loggerName) ? LogRedaction.redactAmounts(text) : LogRedaction.redact(text);
    }

    static boolean isSwapLogger(String loggerName) {
        return loggerName != null && (loggerName.equals(SWAP_LOGGERS) || loggerName.startsWith(SWAP_LOGGERS + "."));
    }
}

package com.sparrowwallet.drongo.log;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * A stack trace with {@link LogRedaction} applied: an exception's message (and its causes') can carry an address, a transaction id or, from the
 * swap service, an amount. The frames themselves are kept as they are.
 */
public class RedactingThrowableConverter extends ThrowableProxyConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return RedactingMessageConverter.redact(event.getLoggerName(), super.convert(event));
    }
}

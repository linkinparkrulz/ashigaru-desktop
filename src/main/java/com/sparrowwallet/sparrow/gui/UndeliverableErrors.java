package com.sparrowwallet.sparrow.gui;

import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/**
 * RxJava's handler for errors nobody is listening for any more. A network call whose caller gave up (a lookup that timed out, a screen that
 * closed) fails afterwards when its connection is closed; RxJava then has no one to give that error to and, by default, hands it to the thread's
 * uncaught-exception handler, which logs it as an ERROR with a stack trace (AD-43). Those are expected, so they are logged at DEBUG on one line.
 * Anything else still goes to the uncaught-exception handler as before.
 */
public final class UndeliverableErrors {
    private static final Logger log = LoggerFactory.getLogger(UndeliverableErrors.class);

    private UndeliverableErrors() {}

    public static void install() {
        RxJavaPlugins.setErrorHandler(error -> {
            if(isExpected(error)) {
                Throwable cause = error.getCause();
                log.debug("A network call ended after its caller stopped waiting: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
                return;
            }
            Thread thread = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
            if(handler != null) {
                handler.uncaughtException(thread, error);
            } else {
                log.error("Undeliverable error", error);
            }
        });
    }

    /** An error from a call nobody waits for any more, caused by I/O, an interrupt or a timeout */
    static boolean isExpected(Throwable error) {
        if(!(error instanceof UndeliverableException)) {
            return false;
        }
        Throwable cause = error.getCause();
        return cause instanceof IOException || cause instanceof InterruptedIOException || cause instanceof InterruptedException
                || cause instanceof TimeoutException;
    }
}

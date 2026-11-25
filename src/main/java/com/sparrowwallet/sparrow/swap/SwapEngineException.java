package com.sparrowwallet.sparrow.swap;

public class SwapEngineException extends Exception {
    public SwapEngineException(String message) {
        super(message);
    }

    public SwapEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}

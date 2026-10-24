package com.sparrowwallet.sparrow.swap;

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * The C interface of the native swap engine (swap-engine/ashigaru-swap/src/lib.rs). Every call
 * takes and returns UTF-8 JSON; every returned string is released with {@link #as_free_string}.
 */
public interface SwapEngineLibrary extends Library {
    Pointer as_init(String configJson);

    Pointer as_list_quotes();

    void as_free_string(Pointer string);
}

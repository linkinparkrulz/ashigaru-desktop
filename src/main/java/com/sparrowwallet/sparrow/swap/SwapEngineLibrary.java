package com.sparrowwallet.sparrow.swap;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * The C interface of the native swap engine (swap-engine/ashigaru-swap/src/lib.rs). Every call
 * takes and returns UTF-8 JSON; every returned string is released with {@link #as_free_string}.
 */
public interface SwapEngineLibrary extends Library {
    /**
     * The engine asking Ashigaru for something. Id 0 is a notification that needs no answer; any
     * other id is answered with {@link #as_respond}. The strings are only valid during the call.
     */
    interface RequestCallback extends Callback {
        void invoke(long requestId, Pointer kind, Pointer payloadJson);
    }

    Pointer as_set_callback(RequestCallback callback);

    Pointer as_respond(long requestId, String answerJson);

    Pointer as_init(String configJson);

    Pointer as_shutdown();

    Pointer as_list_quotes();

    Pointer as_start_swap(String requestJson);

    Pointer as_resume_all();

    Pointer as_list_swaps();

    Pointer as_cancel_refund(String swapId);

    Pointer as_check_nodes();

    void as_free_string(Pointer string);
}

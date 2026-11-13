package com.sparrowwallet.sparrow.swap;

/**
 * Something that can show a {@link SwapReview} to the user, in practice the Swaps screen. The host
 * only hands it a review while {@link #isShowing()}; otherwise it asks in a dialog, so a request
 * can never wait unseen on a screen nobody is looking at.
 */
public interface ReviewPresenter {
    /** True when the user can see this presenter right now */
    boolean isShowing();

    /** Shows the review and keeps it up until {@link #dismiss()} */
    void present(SwapReview review);

    /** Takes the review down: it was answered, expired, or moved to a dialog */
    void dismiss();
}

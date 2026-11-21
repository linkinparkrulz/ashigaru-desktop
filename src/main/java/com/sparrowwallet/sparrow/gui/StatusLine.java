package com.sparrowwallet.sparrow.gui;

import java.util.function.Consumer;

/**
 * What the top bar's message says, and when it goes back to "Ready". A message that is only about a moment (a new block,
 * a new Tor circuit, a login) has to expire on its own: the bar once kept the last one until a wallet happened to finish
 * syncing. A sync is counted: "Syncing" stays while any is running, and a sync whose end was never reported cannot hold it
 * for more than {@link #SYNC_FALLBACK_SECS}.
 *
 * <p>Pure logic: the text goes out through a sink and time comes in through a scheduler, so that it can be tested.
 */
final class StatusLine {
    static final String READY = "Ready";
    static final int SYNC_FALLBACK_SECS = 120;

    /** Runs a task after some seconds, on the thread that owns the sink */
    interface Scheduler {
        void after(int seconds, Runnable task);
    }

    private final Consumer<String> sink;
    private final Scheduler scheduler;

    private String current = READY;
    private int syncs;
    private String syncText;
    //Every change makes older timers stale: a timer only acts if nothing was shown since it was set
    private long generation;

    StatusLine(Consumer<String> sink, Scheduler scheduler) {
        this.sink = sink;
        this.scheduler = scheduler;
    }

    String text() {
        return current;
    }

    /** Shows a message and takes it away again after {@code seconds}, unless something else was shown meanwhile */
    void message(String text, int seconds) {
        show(text);
        long mine = generation;
        scheduler.after(Math.max(1, seconds), () -> {
            if(generation == mine) {
                settle();
            }
        });
    }

    /** A wallet started to sync: "Syncing" stays until every one that started has finished */
    void syncStarted(String name) {
        syncs++;
        syncText = "Syncing " + name + "…";
        show(syncText);
        long mine = generation;
        scheduler.after(SYNC_FALLBACK_SECS, () -> {
            if(generation == mine && syncs > 0) {
                syncs = 0;
                settle();
            }
        });
    }

    /** A sync ended, however it ended */
    void syncFinished() {
        syncs = Math.max(0, syncs - 1);
        if(syncs == 0) {
            show(READY);
        }
    }

    /** Connection lost: syncs that were running will not report, so none is counted any more */
    void connectionLost() {
        syncs = 0;
    }

    /** Nothing more to say: back to what is true now */
    private void settle() {
        show(syncs > 0 && syncText != null ? syncText : READY);
    }

    private void show(String text) {
        generation++;
        current = text;
        sink.accept(text);
    }
}

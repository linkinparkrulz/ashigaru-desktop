package com.sparrowwallet.sparrow.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The top bar's message: it expires, and it does not get stuck on "Syncing" */
class StatusLineTest {
    /** Time that only moves when the test says so */
    private static final class Clock implements StatusLine.Scheduler {
        private final List<int[]> due = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        private int now;

        @Override
        public void after(int seconds, Runnable task) {
            due.add(new int[] {now + seconds});
            tasks.add(task);
        }

        void advance(int seconds) {
            now += seconds;
            for(int i = 0; i < tasks.size(); i++) {
                if(due.get(i)[0] <= now && tasks.get(i) != null) {
                    Runnable task = tasks.get(i);
                    tasks.set(i, null);
                    task.run();
                }
            }
        }
    }

    private static StatusLine line(Clock clock, List<String> shown) {
        return new StatusLine(shown::add, clock);
    }

    @Test
    void aMessageGoesBackToReadyOnItsOwn() {
        Clock clock = new Clock();
        List<String> shown = new ArrayList<>();
        StatusLine line = line(clock, shown);
        line.message("Updating to new block height 900000", 20);
        assertEquals("Updating to new block height 900000", line.text());
        clock.advance(19);
        assertEquals("Updating to new block height 900000", line.text());
        clock.advance(1);
        assertEquals("Ready", line.text());
    }

    @Test
    void aNewerMessageIsNotCutShortByAnOlderTimer() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.message("first", 20);
        clock.advance(15);
        line.message("second", 20);
        clock.advance(10);
        assertEquals("second", line.text(), "the first one's timer ran out, but it is not what is showing");
        clock.advance(10);
        assertEquals("Ready", line.text());
    }

    @Test
    void syncingStaysUntilEverySyncHasFinished() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.syncStarted("Alpha");
        line.syncStarted("Beta");
        assertEquals("Syncing Beta…", line.text());
        line.syncFinished();
        assertEquals("Syncing Beta…", line.text(), "one is still running");
        line.syncFinished();
        assertEquals("Ready", line.text());
        line.syncFinished();
        assertEquals("Ready", line.text(), "an extra finish does not go below nothing running");
    }

    @Test
    void aMessageDuringASyncGivesWayBackToSyncing() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.syncStarted("Alpha");
        line.message("Successfully authenticated", 10);
        assertEquals("Successfully authenticated", line.text());
        clock.advance(10);
        assertEquals("Syncing Alpha…", line.text());
        line.syncFinished();
        assertEquals("Ready", line.text());
    }

    @Test
    void aSyncThatNeverReportsItsEndCannotHoldTheBarForever() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.syncStarted("Alpha");
        clock.advance(StatusLine.SYNC_FALLBACK_SECS - 1);
        assertEquals("Syncing Alpha…", line.text());
        clock.advance(1);
        assertEquals("Ready", line.text());
    }

    @Test
    void aLostConnectionForgetsTheSyncsThatWillNeverFinish() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.syncStarted("Alpha");
        line.connectionLost();
        line.message("Connection failed", 5);
        clock.advance(5);
        assertEquals("Ready", line.text());
    }

    @Test
    void aSyncStartedAfterAMessageIsNotWipedByTheMessagesTimer() {
        Clock clock = new Clock();
        StatusLine line = line(clock, new ArrayList<>());
        line.message("Requested new Tor circuits", 20);
        clock.advance(5);
        line.syncStarted("Alpha");
        clock.advance(20);
        assertEquals("Syncing Alpha…", line.text());
    }
}

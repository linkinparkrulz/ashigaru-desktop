package com.sparrowwallet.sparrow.net;

import io.matthewnelson.kmp.tor.runtime.Action;
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent;
import io.matthewnelson.kmp.tor.runtime.TorListeners;
import io.matthewnelson.kmp.tor.runtime.TorRuntime;
import io.matthewnelson.kmp.tor.runtime.core.OnEvent;
import io.matthewnelson.kmp.tor.runtime.core.ctrl.Reply;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Service to start internal Tor (including a Tor proxy running on localhost:9050, or another port if unavailable)
 *
 * This is a ScheduledService to take advantage of the retry on failure behaviour
 */
public class TorService extends ScheduledService<Tor> {
    private static final Logger log = LoggerFactory.getLogger(TorService.class);

    //The daemon between being launched and becoming Tor.getDefault(). Kept so a retry or a restart can
    //shut it down: otherwise it runs on unseen, holding the data directory the next daemon needs.
    private volatile Tor bootingTor;

    /**
     * Hands over a daemon that was launched but never finished starting, for the caller to destroy.
     */
    public Tor takeBootingTor() {
        Tor tor = bootingTor;
        bootingTor = null;
        return tor;
    }

    @Override
    protected Task<Tor> createTask() {
        return new Task<>() {
            private volatile Exception startupException;

            @Override
            protected Tor call() throws Exception {
                Tor tor = Tor.getDefault();
                if(tor == null) {
                    destroyBootingTor();
                    tor = new Tor();
                    bootingTor = tor;
                    TorRuntime torRuntime = tor.getTorRuntime();
                    //A latch rather than a condition, as Tor can fail or come up before this thread starts waiting
                    CountDownLatch startup = new CountDownLatch(1);
                    RuntimeEvent.Observer<TorListeners> startupObserver = RuntimeEvent.LISTENERS.INSTANCE.observer("startup", OnEvent.Executor.Immediate.INSTANCE, listeners -> {
                        if(!listeners.socks.isEmpty()) {
                            startup.countDown();
                        }
                    });
                    torRuntime.subscribe(startupObserver);
                    torRuntime.enqueue(Action.StartDaemon, throwable -> {
                        if(throwable instanceof Reply.Error replyError && !replyError.replies.isEmpty()) {
                            startupException = new TorStartupException(replyError.replies.get(0).message);
                        } else if(throwable instanceof Exception exception) {
                            startupException = exception;
                        } else {
                            startupException = new Exception(throwable);
                        }
                        log.error("Error starting Tor daemon", throwable);
                        startup.countDown();
                    }, success -> {
                        log.info("Tor daemon started successfully");
                    });

                    try {
                        if(!startup.await(5, TimeUnit.MINUTES)) {
                            throw new TorStartupException("Tor failed to start after 5 minutes, giving up");
                        }

                        if(startupException != null) {
                            throw startupException;
                        }
                    } finally {
                        torRuntime.unsubscribe(startupObserver);
                    }
                    bootingTor = null;
                }

                return tor;
            }
        };
    }

    /** Stops the daemon left by an attempt that failed, waiting briefly so the next one can start cleanly. */
    private void destroyBootingTor() throws InterruptedException {
        Tor previous = takeBootingTor();
        if(previous != null) {
            CountDownLatch destroyed = new CountDownLatch(1);
            previous.stop(destroyed::countDown);
            if(!destroyed.await(15, TimeUnit.SECONDS)) {
                log.warn("Previous Tor daemon did not stop within 15 seconds");
            }
        }
    }
}

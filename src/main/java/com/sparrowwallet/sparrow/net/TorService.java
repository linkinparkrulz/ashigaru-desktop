package com.sparrowwallet.sparrow.net;

import io.matthewnelson.kmp.tor.ext.callback.manager.CallbackTorManager;
import io.matthewnelson.kmp.tor.manager.common.event.TorManagerEvent;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service to start internal Tor (including a Tor proxy running on localhost:9050, or another port if unavailable)
 *
 * This is a ScheduledService to take advantage of the retry on failure behaviour
 */
public class TorService extends ScheduledService<Tor> {
    private static final Logger log = LoggerFactory.getLogger(TorService.class);

    private final ReentrantLock startupLock = new ReentrantLock();
    private final Condition startupCondition = startupLock.newCondition();

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
            private Exception startupException;

            @Override
            protected Tor call() throws Exception {
                Tor tor = Tor.getDefault();
                if(tor == null) {
                    destroyBootingTor();
                    tor = new Tor();
                    bootingTor = tor;
                    CallbackTorManager callbackTorManager = tor.getTorManager();
                    callbackTorManager.addListener(new TorManagerEvent.Listener() {
                        @Override
                        public void managerEventAddressInfo(@NotNull TorManagerEvent.AddressInfo info) {
                            if(!info.isNull) {
                                try {
                                    startupLock.lock();
                                    startupCondition.signalAll();
                                } finally {
                                    startupLock.unlock();
                                }
                            }
                        }
                    });
                    callbackTorManager.start(throwable -> {
                        if(throwable instanceof Exception exception) {
                            startupException = exception;
                        } else {
                            startupException = new Exception(throwable);
                        }
                        log.error("Error", throwable);
                        try {
                            startupLock.lock();
                            startupCondition.signalAll();
                        } finally {
                            startupLock.unlock();
                        }
                    }, success -> {
                        log.info("Tor daemon started successfully");
                    });

                    try {
                        startupLock.lock();
                        if(!startupCondition.await(5, TimeUnit.MINUTES)) {
                            throw new TorStartupException("Tor failed to start after 5 minutes, giving up");
                        }

                        if(startupException != null) {
                            throw startupException;
                        }
                    } finally {
                        startupLock.unlock();
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
            previous.getTorManager().destroy(true, success -> destroyed.countDown());
            if(!destroyed.await(15, TimeUnit.SECONDS)) {
                log.warn("Previous Tor daemon did not stop within 15 seconds");
            }
        }
    }
}

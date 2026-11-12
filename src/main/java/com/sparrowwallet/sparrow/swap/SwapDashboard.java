package com.sparrowwallet.sparrow.swap;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * What the swap dashboard says about one swap, worked out from what is known: where the swap is, where
 * it stands against its timelocks, whether Ashigaru is running it, and what was agreed. It answers
 * five questions in plain words: where are my funds, do I need to act, can I close Ashigaru, what if
 * it stalls, and when does recovery open. No screen code lives here, so each case can be tested.
 *
 * <p>Recovery depends on whether the swap is running. A running swap is cancelled and refunded by
 * Ashigaru itself once the timelock allows, so the user has nothing to do except keep it running. Only
 * a swap that is not running, after its cancel timelock has expired, is cancelled by the user.
 */
public record SwapDashboard(Banner banner, Milestones milestones, Now now, Summary summary, List<String> protocolSteps, int protocolCurrent, boolean canLeave) {
    public enum Tone { INFO, WARN, GOOD }

    public enum Action { CANCEL_AND_REFUND, BACK_TO_OFFERS }

    /** The one line about what the user needs to do, with the action when there is one */
    public record Banner(Tone tone, String title, String text, Cta cta) {}

    public record Cta(Action action, String label, boolean enabled, String note) {}

    /**
     * The named stages. {@code current} is the stage the swap is in; equal to the number of labels when
     * all are done. {@code attention} marks the current stage amber. On the recovery path the first
     * stage is the stall itself, shown as a marker, and {@code stoppedAt} says where the swap stopped.
     */
    public record Milestones(List<String> labels, int current, boolean attention, boolean recovery, String stoppedAt, List<String> notes) {}

    /** What is happening now. The wait is only set while waiting on a network, never while waiting for the user. */
    public record Now(String title, String doing, List<Fact> facts, String waitingOn, Duration waited, Duration usual, String next, String slow) {}

    public record Fact(String label, String value) {}

    /** The right-hand panel: figures, then the answers about funds, closing and recovery */
    public record Summary(String btcCommitted, String xmrLabel, String xmr, String address, String funds, Closing closing,
                          String recoveryTitle, String recoveryText, Double timeline, boolean timelineCancelOpen, List<Fact> identifiers) {}

    public record Closing(Tone tone, String label, String text) {}

    /** Everything the dashboard is worked out from */
    public record Input(String swapId, SwapProgress.Status status, SwapTimelock timelock, boolean running, SwapDetails details,
                        boolean refunding, Instant now) {}

    public static final List<String> MAIN = List.of("Review and authorize", "Lock BTC", "Verify the maker’s XMR", "Claim XMR", "Complete");
    public static final List<String> RECOVERY = List.of("Swap stalled", "Cancel", "Refund to Badbank");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Whether the swap's BTC is, or was, locked on chain */
    private static boolean locked(Input in) {
        SwapProgress.Step step = in.status().step();
        SwapProgress.Step reached = step == SwapProgress.Step.STOPPED ? in.status().stoppedAfter() : step;
        //A timelock reading proves the lock confirmed, so the swap is then locked whatever step it stopped at
        boolean stoppedPublishing = stoppedPublishing(step, in);
        return reached != null && reached.ordinal() >= SwapProgress.Step.LOCK_PUBLISH.ordinal() && reached != SwapProgress.Step.WORKING && !stoppedPublishing
                || step == SwapProgress.Step.REFUND_WAIT || step == SwapProgress.Step.REFUNDING || step == SwapProgress.Step.REFUNDED;
    }

    /**
     * Whether the latest swap takes the Swaps page when it is opened: one Ashigaru is running, or one that
     * holds locked BTC and is not over. A swap that completed, was refunded, or stopped before anything was
     * locked does not: it stays in Your swaps, and the page opens on the offers, ready for the next swap.
     */
    public static boolean opensFirst(Input in) {
        SwapProgress.Step step = in.status().step();
        return in.running() || locked(in) && step != SwapProgress.Step.DONE && step != SwapProgress.Step.REFUNDED;
    }

    public static SwapDashboard of(Input in) {
        SwapProgress.Step step = in.status().step();
        SwapProgress.Step reached = step == SwapProgress.Step.STOPPED ? in.status().stoppedAfter() : step;
        boolean locked = locked(in);
        SwapTimelock timelock = in.timelock();
        boolean cancelOpen = timelock != null && timelock.cancelOpen();
        boolean complete = step == SwapProgress.Step.DONE;
        boolean refunded = step == SwapProgress.Step.REFUNDED;
        boolean stopped = step == SwapProgress.Step.STOPPED;
        boolean recoveringByItself = step == SwapProgress.Step.REFUNDING || step == SwapProgress.Step.REFUND_WAIT && cancelOpen;
        boolean needsYou = stopped && locked && cancelOpen && !in.running();
        boolean recoveryPath = recoveringByItself || needsYou || refunded;

        Banner banner = banner(in, step, locked, cancelOpen, needsYou, recoveringByItself, complete, refunded, stopped);
        Milestones milestones = milestones(in, step, reached, locked, cancelOpen, needsYou, recoveringByItself, complete, refunded, stopped, recoveryPath);
        Now now = now(in, step, locked, cancelOpen, needsYou, recoveringByItself, complete, refunded, stopped);
        Summary summary = summary(in, step, locked, cancelOpen, needsYou, recoveringByItself, complete, refunded, stopped);

        List<String> steps = new ArrayList<>();
        for(SwapProgress.Step main : SwapProgress.MAIN_STEPS) {
            steps.add(main.getTitle());
        }
        SwapProgress.Step shown = reached == null ? step : reached;
        int protocolCurrent = SwapProgress.MAIN_STEPS.indexOf(shown);
        //A swap that is over, or that Ashigaru is not running, never traps the user on its screen: they can go back to
        //the offers, and the swap stays in the list and in the notice above the offers if it still needs them
        boolean canLeave = in.status().finished() || !in.running();
        return new SwapDashboard(banner, milestones, now, summary, steps, protocolCurrent, canLeave);
    }

    private static Banner banner(Input in, SwapProgress.Step step, boolean locked, boolean cancelOpen, boolean needsYou, boolean automatic,
                                 boolean complete, boolean refunded, boolean stopped) {
        if(in.refunding()) {
            return new Banner(Tone.WARN, "Cancelling and refunding", "Ashigaru is publishing the cancel and then the refund. This can take a while: keep Ashigaru open.",
                    new Cta(Action.CANCEL_AND_REFUND, "Cancel and refund", false, "Working"));
        }
        if(complete) {
            return new Banner(Tone.GOOD, "Swap complete", "XMR was sent to your receiving address. It is safe to close Ashigaru.",
                    new Cta(Action.BACK_TO_OFFERS, "Back to offers", true, null));
        }
        if(refunded) {
            return new Banner(Tone.GOOD, "Refunded to Badbank", in.status().detail() == null || in.status().detail().isEmpty()
                    ? "Your BTC came back to Badbank, minus network fees." : in.status().detail(),
                    new Cta(Action.BACK_TO_OFFERS, "Back to offers", true, null));
        }
        if(automatic) {
            return new Banner(Tone.WARN, "This swap has stalled. Ashigaru is cancelling it for you.",
                    "The cancel timelock has expired. Ashigaru cancels and then refunds to Badbank by itself.", null);
        }
        if(step == SwapProgress.Step.REFUND_WAIT) {
            String when = timelock(in) == null ? SwapCopy.cancelAfter() + " after your lock confirms" : SwapCopy.about(timelock(in).remaining());
            return new Banner(Tone.WARN, "This swap did not complete", "Ashigaru cancels it for you once the timelock expires, in " + when + ". Keep Ashigaru open.", null);
        }
        if(needsYou) {
            return new Banner(Tone.WARN, "This swap has stopped. You can cancel it now.",
                    "Ashigaru is not running this swap, so it will not refund by itself. Cancelling publishes the cancel and then the refund.",
                    new Cta(Action.CANCEL_AND_REFUND, "Cancel and refund", true, null));
        }
        if(stoppedPublishing(step, in)) {
            return new Banner(Tone.WARN, "The lock may not have been published",
                    "This swap stopped while publishing the BTC lock, usually because the Bitcoin server could not be reached. Ashigaru cannot tell whether the lock got out. "
                            + "Check Badbank: if the coin is still there, nothing was locked.",
                    new Cta(Action.BACK_TO_OFFERS, "Back to offers", true, null));
        }
        if(stopped && locked) {
            Cta cta = new Cta(Action.CANCEL_AND_REFUND, "Cancel and refund", false,
                    timelock(in) == null ? "Opens " + SwapCopy.cancelAfter() + " after the lock confirms" : "Available in " + SwapCopy.about(timelock(in).remaining()));
            String text = timelock(in) == null
                    ? "Cancelling opens " + SwapCopy.cancelAfter() + " after your lock confirms. Turn swaps on or reopen Ashigaru to resume the swap before then."
                    : "Cancel opens in " + SwapCopy.about(timelock(in).remaining()) + ". Turn swaps on or reopen Ashigaru to resume the swap before then.";
            return new Banner(Tone.WARN, "This swap has stopped, and cancelling is not open yet.", text, cta);
        }
        if(stopped) {
            return new Banner(Tone.WARN, "This swap stopped", "Nothing was locked: your coin is still in Badbank and can be swapped again.",
                    new Cta(Action.BACK_TO_OFFERS, "Back to offers", true, null));
        }
        return switch(step) {
            case NODES -> new Banner(Tone.INFO, "Nothing for you to do", "Ashigaru is checking the Monero nodes through Tor.", null);
            case TERMS -> new Banner(Tone.INFO, "Nothing for you to do", "Ashigaru is agreeing the terms with the maker over Tor.", null);
            case APPROVAL -> new Banner(Tone.WARN, "Waiting for you", "Ashigaru is asking you to approve the final terms. Nothing is locked until you approve.", null);
            case LOCK_PUBLISH -> new Banner(Tone.INFO, "Nothing for you to do", "Ashigaru is sending your signed lock to the Bitcoin network.", null);
            case LOCK_CONFIRM -> new Banner(Tone.INFO, "Nothing for you to do", "Keep Ashigaru open while your lock confirms.", null);
            case MAKER_LOCK -> new Banner(Tone.INFO, "Nothing for you to do", "Keep Ashigaru open. The maker is locking its XMR.", null);
            case MAKER_CONFIRM -> new Banner(Tone.INFO, "Nothing for you to do", "Keep Ashigaru open. Your BTC is locked and the maker’s XMR is being confirmed.", null);
            case RELEASE -> new Banner(Tone.INFO, "Nothing for you to do", "Keep Ashigaru open. It is releasing its side so the maker can take the BTC.", null);
            case CLAIM -> new Banner(Tone.INFO, "Nothing for you to do", "Keep Ashigaru open until your XMR is sent.", null);
            default -> new Banner(Tone.INFO, "Nothing for you to do", "The swap is progressing.", null);
        };
    }

    private static Milestones milestones(Input in, SwapProgress.Step step, SwapProgress.Step reached, boolean locked, boolean cancelOpen, boolean needsYou,
                                         boolean automatic, boolean complete, boolean refunded, boolean stopped, boolean recoveryPath) {
        if(recoveryPath) {
            int current = refunded ? RECOVERY.size() : 1;
            List<String> notes = List.of(refunded ? "Cancelled" : stopped ? "Swap stopped" : "Timelock expired",
                    refunded ? "Done" : needsYou ? "Ready to publish" : "Publishing", refunded ? "Refunded" : "Not started");
            return new Milestones(RECOVERY, current, true, true, stoppedAt(reached), notes);
        }
        //A swap that stopped, or is waiting to cancel, is shown at the stage it reached; if that is not known, while it waited on the maker
        SwapProgress.Step shown = step;
        if(stopped || step == SwapProgress.Step.REFUND_WAIT) {
            shown = reached == null || reached == SwapProgress.Step.STOPPED || reached == SwapProgress.Step.REFUND_WAIT ? SwapProgress.Step.MAKER_LOCK : reached;
        }
        int index = complete ? MAIN.size() : mainIndex(shown);
        boolean attention = stopped || step == SwapProgress.Step.REFUND_WAIT || step == SwapProgress.Step.APPROVAL;
        List<String> notes = new ArrayList<>();
        for(int i = 0; i < MAIN.size(); i++) {
            notes.add(i == index ? (stopped ? "Swap stopped" : step == SwapProgress.Step.APPROVAL ? "Waiting for you" : "In progress") : i < index ? "Done" : "");
        }
        return new Milestones(MAIN, index, attention, false, null, notes);
    }

    /** The named stage a step belongs to ("Lock BTC"), or null for a step outside the main path */
    public static String milestone(SwapProgress.Step step) {
        return switch(step) {
            case NODES, TERMS, APPROVAL, LOCK_PUBLISH, LOCK_CONFIRM, MAKER_LOCK, MAKER_CONFIRM, RELEASE, CLAIM, DONE -> MAIN.get(mainIndex(step));
            default -> null;
        };
    }

    private static int mainIndex(SwapProgress.Step step) {
        return switch(step) {
            case NODES, TERMS, APPROVAL, WORKING, STOPPED -> 0;
            case LOCK_PUBLISH, LOCK_CONFIRM -> 1;
            case MAKER_LOCK, MAKER_CONFIRM -> 2;
            case RELEASE, CLAIM -> 3;
            case DONE -> 4;
            default -> 2;
        };
    }

    private static String stoppedAt(SwapProgress.Step reached) {
        if(reached == null || reached == SwapProgress.Step.STOPPED || reached == SwapProgress.Step.REFUND_WAIT) {
            return null;
        }
        int index = mainIndex(reached);
        return "Stopped at step " + (index + 1) + " of " + MAIN.size() + ": " + MAIN.get(index);
    }

    private static Now now(Input in, SwapProgress.Step step, boolean locked, boolean cancelOpen, boolean needsYou, boolean automatic, boolean complete,
                           boolean refunded, boolean stopped) {
        SwapDetails details = in.details();
        if(complete) {
            List<Fact> facts = new ArrayList<>();
            if(details != null && details.piconero() > 0) {
                facts.add(new Fact("XMR sent", BadbankSwapHost.xmr(details.piconero()) + " XMR"));
            }
            if(details != null && details.xmrAddress() != null) {
                facts.add(new Fact("To", shortAddress(details.xmrAddress())));
            }
            String tx = after(in.status().detail(), "XMR transaction ");
            if(!tx.isEmpty()) {
                facts.add(new Fact("XMR transaction", tx));
            }
            facts.add(new Fact("Completed", WHEN.format(in.status().since())));
            return new Now("Receipt", "Monero normally makes received funds spendable after about 20 minutes (10 confirmations). Your receiving wallet shows when they are ready.",
                    facts, null, null, null, null, null);
        }
        if(refunded) {
            List<Fact> facts = new ArrayList<>();
            if(in.status().detail() != null && !in.status().detail().isEmpty()) {
                facts.add(new Fact("Refund", in.status().detail()));
            }
            facts.add(new Fact("Completed", WHEN.format(in.status().since())));
            return new Now("Refunded to Badbank", "The swap did not complete, and your BTC came back to Badbank.", facts, null, null, null, null, null);
        }
        if(needsYou || (stopped && locked) || automatic || step == SwapProgress.Step.REFUND_WAIT) {
            return recoveryNow(in, step, cancelOpen, needsYou, automatic, stopped);
        }
        if(stopped) {
            //The banner already says it stopped; this says why
            return new Now("Why it stopped", in.status().detail(), List.of(), null, null, null, null, null);
        }
        Duration waited = minutes(Duration.between(in.status().since(), in.now()));
        boolean network = step != SwapProgress.Step.APPROVAL && step != SwapProgress.Step.WORKING && !in.status().finished();
        String slow = null;
        if(in.status().isSlow(in.now())) {
            slow = step == SwapProgress.Step.NODES
                    ? "This is taking longer than usual: a Monero node may be unreachable through Tor. Tools → Logs → Swap engine says which; if one keeps failing, untick it in Settings → Swaps. Nothing is locked yet."
                    : "This is taking longer than usual (normally under " + step.getUsual().toMinutes() + " minutes). Tools → Logs → Swap engine shows what it is waiting for.";
        }
        return new Now(step.getTitle(), step.getDoing(), facts(step, in.status().detail()), network ? waitingOn(step) : null, waited,
                step.getUsual(), step.getNext().isEmpty() ? null : step.getNext(), slow);
    }

    private static Now recoveryNow(Input in, SwapProgress.Step step, boolean cancelOpen, boolean needsYou, boolean automatic, boolean stopped) {
        SwapDetails details = in.details();
        List<Fact> facts = new ArrayList<>();
        if(details != null) {
            facts.add(new Fact("Estimated BTC returned", "up to " + BadbankSwapHost.btc(details.refundSats()) + " BTC"));
            facts.add(new Fact("Network fees", "deducted from the refund"));
            if(details.hasDeposit()) {
                facts.add(new Fact("Maker may keep (deposit)", BadbankSwapHost.btc(details.amnestySats()) + " BTC (" + BadbankSwapHost.percent(details.amnestySats(), details.lockSats()) + ")"));
            }
        }
        facts.add(new Fact("Refund window", SwapCopy.refundWindow() + " after the cancel confirms"));
        if(needsYou) {
            return new Now("Cancel is available", "Ashigaru stopped running this swap. One click publishes the cancel and then the refund, and keeps going until your BTC is back in Badbank.",
                    facts, null, null, null, "Keep Ashigaru running until the refund confirms. With swaps on in Settings, a stopped swap resumes when Ashigaru starts.", null);
        }
        if(automatic) {
            Duration waited = minutes(Duration.between(in.status().since(), in.now()));
            return new Now("Cancelling and refunding",
                    "Ashigaru publishes the cancel, waits for it to confirm, then publishes the refund. You do not need to do anything, but Ashigaru must stay running until the refund confirms.",
                    facts, "the Bitcoin network", waited, SwapProgress.Step.REFUNDING.getUsual(), "The refund confirms on Bitcoin and returns to Badbank.", null);
        }
        if(stopped) {
            SwapTimelock timelock = timelock(in);
            List<Fact> waiting = new ArrayList<>();
            if(timelock != null) {
                waiting.add(new Fact("Cancel opens in", SwapCopy.about(timelock.remaining())));
                waiting.add(new Fact("Blocks left", String.valueOf(timelock.blocksLeft())));
            }
            return new Now("Waiting for the cancel timelock",
                    "Your BTC is locked. The cancel timelock has not expired, so there is nothing to cancel yet. When it does, a running swap cancels and refunds by itself, and a stopped swap can be cancelled here.",
                    waiting, null, null, null, "Resume the swap, or come back when cancel opens.", null);
        }
        //Waiting to cancel, with the swap running
        SwapTimelock timelock = timelock(in);
        if(timelock != null) {
            facts.add(0, new Fact("Cancel opens in", SwapCopy.about(timelock.remaining())));
        }
        return new Now(step.getTitle(), step.getDoing(), facts, null, null, null, step.getNext(), null);
    }

    /** Stopped while publishing the lock: the engine saves the signed lock first, so whether it got out is not known */
    private static boolean stoppedPublishing(SwapProgress.Step step, Input in) {
        return step == SwapProgress.Step.STOPPED && in.status().stoppedAfter() == SwapProgress.Step.LOCK_PUBLISH && in.timelock() == null;
    }

    private static Summary summary(Input in, SwapProgress.Step step, boolean locked, boolean cancelOpen, boolean needsYou, boolean automatic,
                                   boolean complete, boolean refunded, boolean stopped) {
        SwapDetails details = in.details();
        SwapTimelock timelock = timelock(in);
        String btc = details == null ? "—" : BadbankSwapHost.btc(details.lockSats());
        boolean xmrKnown = details != null && details.piconero() > 0;
        String xmr = xmrKnown ? BadbankSwapHost.xmr(details.piconero()) : "Not agreed yet";
        String address = details == null || details.xmrAddress() == null ? "—" : shortAddress(details.xmrAddress());
        List<Fact> ids = new ArrayList<>();
        ids.add(new Fact("Swap id", in.swapId()));
        if(details != null && details.maker() != null && !details.maker().isEmpty()) {
            ids.add(new Fact("Maker", details.maker()));
        }
        if(details != null && details.xmrAddress() != null) {
            ids.add(new Fact("Receiving address", details.xmrAddress()));
        }

        String funds;
        Closing closing;
        if(complete) {
            funds = "The XMR is at your address. The BTC now belongs to the maker.";
            closing = new Closing(Tone.GOOD, "Safe to close", "Nothing is left to do.");
        } else if(refunded) {
            funds = "Back in Badbank.";
            closing = new Closing(Tone.GOOD, "Safe to close", "Your BTC is back in Badbank.");
        } else if(stoppedPublishing(step, in)) {
            funds = "Probably still in Badbank: the lock was not confirmed as published. Check your Badbank balance.";
            closing = new Closing(Tone.WARN, "Check Badbank", "If the coin has left Badbank the lock was published, so keep Ashigaru open to let the swap resume. If it is still there, nothing was locked.");
        } else if(!locked) {
            funds = stopped ? "Still in Badbank. Nothing was locked." : "Still in Badbank. Nothing is locked yet.";
            closing = new Closing(Tone.GOOD, "Safe to close", stopped ? "Nothing was locked. Your coin is still in Badbank."
                    : "Nothing is locked yet. Closing stops the setup, and your coin stays in Badbank.");
        } else if(step == SwapProgress.Step.REFUNDING) {
            funds = "Being returned to Badbank.";
            closing = new Closing(Tone.WARN, "Keep open", "Ashigaru publishes the cancel and the refund, so it must stay running.");
        } else if(automatic || needsYou) {
            funds = needsYou ? "Still locked on Bitcoin. It is not lost, and it can be refunded now." : "Still locked on Bitcoin while the cancel confirms. It is not lost.";
            closing = new Closing(Tone.WARN, "Keep open", "Ashigaru publishes the cancel and the refund, so it must stay running.");
        } else if(stopped) {
            funds = "Locked on Bitcoin. Nobody can spend it until the swap finishes or is cancelled.";
            closing = new Closing(Tone.WARN, "Keep open", "A stopped swap does nothing until it is resumed or cancelled.");
        } else {
            funds = switch(step) {
                case LOCK_PUBLISH -> "Being sent to the swap’s lock transaction on Bitcoin.";
                case LOCK_CONFIRM -> "In the swap’s lock transaction on Bitcoin. Nobody can spend it until the swap finishes or is cancelled.";
                case MAKER_LOCK -> "Your BTC is locked on Bitcoin. The maker is locking its XMR on Monero.";
                case MAKER_CONFIRM -> "Your BTC is locked on Bitcoin. The maker’s XMR is locked on Monero and waiting for confirmations.";
                case RELEASE -> "Your BTC is locked while Ashigaru releases its side, now that the XMR is safely locked.";
                case CLAIM -> "The maker has the key to the BTC. Your XMR is being sent to your address.";
                case REFUND_WAIT -> "Still locked on Bitcoin. It is not lost.";
                default -> "Locked on Bitcoin until the swap finishes or is cancelled.";
            };
            closing = new Closing(Tone.WARN, "Keep open", "The swap pauses while Ashigaru is closed. With swaps on, it resumes when Ashigaru starts.");
        }

        String recoveryTitle = complete || refunded || needsYou || automatic || timelock != null && timelock.cancelOpen() ? "Recovery" : "If the swap stalls";
        String recovery;
        Double position = null;
        boolean cancelMark = false;
        if(complete) {
            recovery = "Not needed. The swap is complete.";
        } else if(refunded) {
            recovery = "Not needed. The refund is complete.";
        } else if(stoppedPublishing(step, in)) {
            recoveryTitle = "If the lock was published";
            recovery = "Cancellation opens " + SwapCopy.cancelAfter() + " after the lock confirms. If it was not published there is nothing to recover: "
                    + "the maker waits only about 10 minutes for the lock, and after that Ashigaru closes the swap when the swap engine next starts instead of publishing a late lock.";
        } else if(!locked) {
            recovery = "Nothing is locked yet, so there is nothing to recover.";
        } else if(timelock != null && timelock.phase() == SwapTimelock.Phase.CANCEL_OPEN) {
            cancelMark = true;
            position = 0.5 + 0.5 * (1 - Math.min(1d, (double)timelock.blocksLeft() / SwapTimelock.PUNISH_BLOCKS));
            recovery = needsYou
                    ? "Cancel is available now. Do it soon: the refund must be published within " + SwapCopy.about(timelock.remaining()) + " of the cancel confirming."
                    : "Automatic. The refund must be published within " + SwapCopy.about(timelock.remaining()) + ", and Ashigaru does this while it is running.";
        } else if(timelock != null && timelock.phase() == SwapTimelock.Phase.BEFORE_CANCEL) {
            position = 0.5 * (1 - Math.min(1d, (double)timelock.blocksLeft() / SwapTimelock.CANCEL_BLOCKS));
            recovery = "If the maker does not complete, cancellation becomes available in " + SwapCopy.about(timelock.remaining()) + ". "
                    + (stopped ? "Turn swaps on or reopen Ashigaru to resume the swap before then." : "Ashigaru then cancels and refunds for you while it is running.");
        } else if(timelock != null && timelock.phase() == SwapTimelock.Phase.PUNISH) {
            recovery = "The refund window has passed. If the BTC was not refunded in time, the maker can take it.";
        } else if(timelock != null) {
            recovery = "Your refund is in. The maker’s deposit can be claimed once its short timelock expires, unless the maker withholds it.";
        } else {
            recovery = "Cancellation opens " + SwapCopy.cancelAfter() + " after your lock confirms. The exact time appears here once it does.";
        }
        return new Summary(btc, complete ? "XMR sent" : "XMR expected", xmr, address, funds, closing, recoveryTitle, recovery, position, cancelMark, ids);
    }

    private static List<Fact> facts(SwapProgress.Step step, String detail) {
        if(detail == null || detail.isBlank()) {
            return List.of();
        }
        String[] parts = detail.split(" · ", 2);
        return switch(step) {
            case LOCK_CONFIRM -> parts.length == 2
                    ? List.of(new Fact("Bitcoin lock transaction", parts[0]), new Fact("Transaction", parts[1]))
                    : List.of(new Fact("Bitcoin lock transaction", parts[0]));
            case MAKER_LOCK -> List.of(new Fact("Maker’s XMR lock", detail));
            case MAKER_CONFIRM -> List.of(new Fact("Maker’s XMR lock (their transaction)", detail));
            case CLAIM -> List.of(new Fact("XMR transaction", detail));
            default -> List.of(new Fact("Detail", detail));
        };
    }

    private static String waitingOn(SwapProgress.Step step) {
        return switch(step) {
            case NODES -> "the Monero nodes, through Tor";
            case TERMS -> "the maker, over Tor";
            case LOCK_PUBLISH, LOCK_CONFIRM -> "the Bitcoin network";
            case MAKER_LOCK, MAKER_CONFIRM, CLAIM -> "the Monero network";
            case RELEASE -> "the maker";
            default -> "the network";
        };
    }

    /** Whole minutes: the time on a step is shown as "12 min so far", so the screen only changes when that does */
    private static Duration minutes(Duration duration) {
        return Duration.ofMinutes(Math.max(0, duration.toMinutes()));
    }

    private static SwapTimelock timelock(Input in) {
        return in.timelock();
    }

    private static String after(String text, String prefix) {
        return text != null && text.startsWith(prefix) ? text.substring(prefix.length()) : "";
    }

    static String shortAddress(String address) {
        return address == null || address.length() <= 14 ? (address == null ? "" : address) : address.substring(0, 6) + "…" + address.substring(address.length() - 6);
    }
}

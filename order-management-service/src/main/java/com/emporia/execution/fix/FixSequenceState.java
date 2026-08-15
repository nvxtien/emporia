package com.emporia.execution.fix;

import com.emporia.execution.FixSessionStateStore;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The two sequence numbers a FIX session keeps, and their durable copies.
 *
 * <p>Direction-agnostic: an initiator and an acceptor both count outgoing
 * messages and both expect incoming ones in order. What differs is only the key
 * the numbers are stored under - a MIC when Emporia dialled the venue, the
 * counterparty's {@code SenderCompID} when it dialled Emporia - so the key is a
 * constructor argument rather than a field type.
 *
 * <h2>This class is not thread-safe, deliberately</h2>
 * <p>Its callers hold the session monitor. {@code send} and
 * {@code handleResendRequest} are both {@code synchronized} on the session, and
 * that is what lets a {@code ResendRequest} with {@code EndSeqNo=0} resolve
 * "through current" against a snapshot no concurrent send can invalidate -
 * order commands arrive on a dispatcher shard thread, independent of the
 * session's own read loop. Adding a second lock here would not add safety and
 * would invite the belief that the session lock is unnecessary.
 */
public final class FixSequenceState {

    private final String sessionKey;
    private final FixSessionStateStore store;
    private final AtomicInteger outgoing = new AtomicInteger(1);
    private final AtomicInteger incoming = new AtomicInteger(1);

    public FixSequenceState(String sessionKey, FixSessionStateStore store) {
        this.sessionKey = sessionKey;
        this.store = store;
    }

    /**
     * Takes the next outgoing number, persisting the advance <b>before</b> the
     * caller attempts its write.
     *
     * <p>Delivery is ambiguous the moment {@code write()} is called - the
     * counterparty may have received a fragment before a failure - so a
     * reconnect must never hand this number out again. Persisting only on
     * success would let a reload-on-reconnect reuse it.
     */
    public int claimOutgoing() {
        int seqNum = outgoing.getAndIncrement();
        store.saveOutgoing(sessionKey, outgoing.get());
        return seqNum;
    }

    /** Highest outgoing number actually sent, for resolving {@code EndSeqNo=0}. */
    public int lastOutgoing() {
        return outgoing.get() - 1;
    }

    /** The next incoming number this session expects. */
    public int expectedIncoming() {
        return incoming.get();
    }

    /** Records that {@code seqNum} arrived in order and is about to be handled. */
    public void acceptIncoming(int seqNum) {
        incoming.set(seqNum + 1);
        store.saveIncoming(sessionKey, seqNum + 1);
    }

    /**
     * Applies a {@code SequenceReset}, which may only ever move <b>forward</b>.
     *
     * <p>A lower {@code NewSeqNo} would regress the expectation and make later,
     * legitimate messages look like gaps.
     *
     * @return whether the reset was applied
     */
    public boolean resetIncomingForward(int newSeqNo) {
        if (newSeqNo <= incoming.get()) return false;
        incoming.set(newSeqNo);
        store.saveIncoming(sessionKey, newSeqNo);
        return true;
    }

    /** Reloads both numbers at (re)connect, and reports what was found. */
    public FixSessionStateStore.SessionState restoreForToday() {
        FixSessionStateStore.SessionState state = store.loadForToday(sessionKey);
        outgoing.set(state.outgoingSeqNum());
        incoming.set(state.incomingSeqNum());
        return state;
    }
}

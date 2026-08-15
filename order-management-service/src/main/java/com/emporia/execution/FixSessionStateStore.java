package com.emporia.execution;

import java.time.LocalDate;

/**
 * Persists per-session FIX sequence numbers so a restart or reconnect resumes
 * the session instead of restarting MsgSeqNum at 1, which would desync from any
 * counterparty that expects a continuous sequence within a trading day.
 *
 * <p>Public because it is a port of the session layer rather than of the venue
 * gateway: {@code com.emporia.execution.fix} needs it, and an inbound session
 * will need it under a different key - the counterparty's SenderCompID instead
 * of a MIC. The parameter is still named {@code mic} here only because renaming
 * it is a change to every implementation and belongs with the acceptor work.
 */
public interface FixSessionStateStore {
    /**
     * Loads the session state for today (UTC). A row from a prior day is
     * treated as a new session and reset to sequence 1/1, matching the daily
     * reset convention most venues use.
     */
    SessionState loadForToday(String mic);

    /** Records the next outgoing MsgSeqNum to use after a message was sent. */
    void saveOutgoing(String mic, int nextSeqNum);

    /** Records the next incoming MsgSeqNum expected after a message arrived. */
    void saveIncoming(String mic, int nextSeqNum);

    record SessionState(LocalDate sessionDate, int outgoingSeqNum, int incomingSeqNum, boolean freshSession) {
    }
}

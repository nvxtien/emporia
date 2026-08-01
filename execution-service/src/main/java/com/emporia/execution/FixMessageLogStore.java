package com.emporia.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Persists outbound FIX application messages (orders, replaces, cancels) so
 * they can be retransmitted verbatim if the counterparty sends a
 * ResendRequest for a range that includes them. Admin/session messages
 * (Logon, Heartbeat, TestRequest, Logout, Reject, ResendRequest,
 * SequenceReset) are never stored: on resend, a gap in the log is exactly
 * the range that gets bridged with a SequenceReset-GapFill instead of a
 * literal replay.
 */
interface FixMessageLogStore {
    void record(String mic, int seqNum, String msgType, LinkedHashMap<Integer, String> fields, Instant sentAt);

    List<LoggedMessage> range(String mic, int beginSeqNumInclusive, int endSeqNumInclusive);

    /** Drops a venue's log, called when a new session day starts and sequence numbers restart at 1. */
    void clear(String mic);

    record LoggedMessage(int seqNum, String msgType, LinkedHashMap<Integer, String> fields, Instant sentAt) {
    }
}

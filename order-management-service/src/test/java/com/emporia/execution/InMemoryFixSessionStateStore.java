package com.emporia.execution;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryFixSessionStateStore implements FixSessionStateStore {
    private final Map<String, SessionState> state = new ConcurrentHashMap<>();

    @Override
    public SessionState loadForToday(String mic) {
        return state.computeIfAbsent(mic, key -> new SessionState(LocalDate.now(), 1, 1, true));
    }

    @Override
    public void saveOutgoing(String mic, int nextSeqNum) {
        state.compute(mic, (key, current) -> new SessionState(
                current == null ? LocalDate.now() : current.sessionDate(),
                nextSeqNum,
                current == null ? 1 : current.incomingSeqNum(),
                false));
    }

    @Override
    public void saveIncoming(String mic, int nextSeqNum) {
        state.compute(mic, (key, current) -> new SessionState(
                current == null ? LocalDate.now() : current.sessionDate(),
                current == null ? 1 : current.outgoingSeqNum(),
                nextSeqNum,
                false));
    }
}

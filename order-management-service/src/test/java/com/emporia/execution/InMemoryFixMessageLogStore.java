package com.emporia.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryFixMessageLogStore implements FixMessageLogStore {
    private final Map<String, Map<Integer, LoggedMessage>> byMic = new ConcurrentHashMap<>();

    @Override
    public void record(String mic, int seqNum, String msgType, LinkedHashMap<Integer, String> fields, Instant sentAt) {
        byMic.computeIfAbsent(mic, key -> new ConcurrentHashMap<>())
                .put(seqNum, new LoggedMessage(seqNum, msgType, new LinkedHashMap<>(fields), sentAt));
    }

    @Override
    public List<LoggedMessage> range(String mic, int beginSeqNumInclusive, int endSeqNumInclusive) {
        List<LoggedMessage> result = new ArrayList<>();
        Map<Integer, LoggedMessage> messages = byMic.getOrDefault(mic, Map.of());
        for (int seq = beginSeqNumInclusive; seq <= endSeqNumInclusive; seq++) {
            LoggedMessage message = messages.get(seq);
            if (message != null) result.add(message);
        }
        return result;
    }

    @Override
    public void clear(String mic) {
        byMic.remove(mic);
    }
}

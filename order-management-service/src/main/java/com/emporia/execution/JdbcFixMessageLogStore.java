package com.emporia.execution;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

@Component
class JdbcFixMessageLogStore implements FixMessageLogStore {
    private static final char SOH = 1;

    private final JdbcTemplate jdbc;

    JdbcFixMessageLogStore(@Qualifier("executionJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String mic, int seqNum, String msgType, LinkedHashMap<Integer, String> fields, Instant sentAt) {
        jdbc.update("INSERT INTO fix_message_log (mic, seq_num, msg_type, raw_message, sent_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                mic, seqNum, msgType, serialize(fields), sentAt);
    }

    @Override
    public List<LoggedMessage> range(String mic, int beginSeqNumInclusive, int endSeqNumInclusive) {
        return jdbc.query("SELECT seq_num, msg_type, raw_message, sent_at FROM fix_message_log "
                        + "WHERE mic = ? AND seq_num >= ? AND seq_num <= ? ORDER BY seq_num",
                (resultSet, rowNumber) -> new LoggedMessage(resultSet.getInt("seq_num"),
                        resultSet.getString("msg_type"), deserialize(resultSet.getString("raw_message")),
                        resultSet.getObject("sent_at", Instant.class)),
                mic, beginSeqNumInclusive, endSeqNumInclusive);
    }

    @Override
    public void clear(String mic) {
        jdbc.update("DELETE FROM fix_message_log WHERE mic = ?", mic);
    }

    private static String serialize(LinkedHashMap<Integer, String> fields) {
        StringBuilder builder = new StringBuilder();
        fields.forEach((tag, value) -> builder.append(tag).append('=').append(value).append(SOH));
        return builder.toString();
    }

    private static LinkedHashMap<Integer, String> deserialize(String raw) {
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        if (raw.isEmpty()) return fields;
        for (String field : raw.split(String.valueOf(SOH))) {
            int separator = field.indexOf('=');
            if (separator > 0) fields.put(Integer.parseInt(field.substring(0, separator)), field.substring(separator + 1));
        }
        return fields;
    }
}

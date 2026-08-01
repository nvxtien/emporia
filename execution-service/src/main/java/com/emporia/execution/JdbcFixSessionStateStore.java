package com.emporia.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Component
class JdbcFixSessionStateStore implements FixSessionStateStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    JdbcFixSessionStateStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcFixSessionStateStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public synchronized SessionState loadForToday(String mic) {
        LocalDate today = LocalDate.now(clock);
        List<Row> rows = jdbc.query(
                "SELECT session_date, outgoing_seq_num, incoming_seq_num FROM fix_session_state WHERE mic = ?",
                (resultSet, rowNumber) -> new Row(resultSet.getObject("session_date", LocalDate.class),
                        resultSet.getInt("outgoing_seq_num"), resultSet.getInt("incoming_seq_num")),
                mic);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO fix_session_state "
                            + "(mic, session_date, outgoing_seq_num, incoming_seq_num, updated_at) "
                            + "VALUES (?, ?, 1, 1, ?)",
                    mic, today, Instant.now(clock));
            return new SessionState(today, 1, 1, true);
        }
        Row row = rows.get(0);
        if (row.sessionDate.isEqual(today)) {
            return new SessionState(today, row.outgoingSeqNum, row.incomingSeqNum, false);
        }
        jdbc.update("UPDATE fix_session_state "
                        + "SET session_date = ?, outgoing_seq_num = 1, incoming_seq_num = 1, updated_at = ? "
                        + "WHERE mic = ?",
                today, Instant.now(clock), mic);
        return new SessionState(today, 1, 1, true);
    }

    @Override
    public void saveOutgoing(String mic, int nextSeqNum) {
        jdbc.update("UPDATE fix_session_state SET outgoing_seq_num = ?, updated_at = ? WHERE mic = ?",
                nextSeqNum, Instant.now(clock), mic);
    }

    @Override
    public void saveIncoming(String mic, int nextSeqNum) {
        jdbc.update("UPDATE fix_session_state SET incoming_seq_num = ?, updated_at = ? WHERE mic = ?",
                nextSeqNum, Instant.now(clock), mic);
    }

    private record Row(LocalDate sessionDate, int outgoingSeqNum, int incomingSeqNum) {
    }
}

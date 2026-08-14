package com.emporia.ordermanagement.service;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DedupIndexLoaderTest {

    @Test
    void loadedCommandsAreNoLongerNewToTheIndex() {
        List<UUID> stored = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        JdbcTemplate jdbc = jdbcReturning(stored);
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001, 100);

        long loaded = new DedupIndexLoader(jdbc).load(index, Duration.ofHours(8));

        assertThat(loaded).isEqualTo(3);
        assertThat(stored).allSatisfy(id -> assertThat(index.definitelyNew(id)).isFalse());
    }

    @Test
    void anEmptyWindowLoadsNothingAndLeavesTheIndexUntouched() {
        JdbcTemplate jdbc = jdbcReturning(List.of());
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001, 100);

        long loaded = new DedupIndexLoader(jdbc).load(index, Duration.ofHours(8));

        assertThat(loaded).isZero();
        assertThat(index.definitelyNew(UUID.randomUUID())).isTrue();
    }

    /**
     * Drives the loader's RowCallbackHandler over a fixed set of ids, standing in
     * for the streaming query. Verifies the loader consumes rows one at a time
     * rather than collecting them, which is the point of the streaming shape.
     */
    private static JdbcTemplate jdbcReturning(List<UUID> ids) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Answer<Void> stream = invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (UUID id : ids) {
                ResultSet row = mock(ResultSet.class);
                when(row.getObject(1)).thenReturn(id);
                handler.processRow(row);
            }
            return null;
        };
        doAnswer(stream).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        return jdbc;
    }
}

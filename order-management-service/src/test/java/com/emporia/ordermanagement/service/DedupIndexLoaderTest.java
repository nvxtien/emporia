package com.emporia.ordermanagement.service;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DedupIndexLoaderTest {

    private static final Duration WINDOW = Duration.ofHours(24);

    @Test
    void loadedCommandsAreNoLongerNewToTheIndex() {
        List<UUID> stored = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        JdbcTemplate jdbc = jdbcReturning(Map.of("processed_order_command", stored));
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001);

        long loaded = new DedupIndexLoader(jdbc).load(index, WINDOW);

        assertThat(loaded).isEqualTo(3);
        assertThat(stored).allSatisfy(id -> assertThat(index.definitelyNew(id)).isFalse());
    }

    /**
     * The 409 duplicate-order guard asks the same index the idempotency check
     * does. Loading only command ids left it answering "never seen" for every
     * order that existed before this process started.
     */
    @Test
    void orderIdsInTheWindowAreLoadedAlongsideCommandIds() {
        UUID command = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        JdbcTemplate jdbc = jdbcReturning(Map.of(
                "processed_order_command", List.of(command),
                "created_at", List.of(order)));
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001);

        long loaded = new DedupIndexLoader(jdbc).load(index, WINDOW);

        assertThat(loaded).isEqualTo(2);
        assertThat(index.definitelyNew(command)).isFalse();
        assertThat(index.definitelyNew(order)).isFalse();
    }

    /**
     * A strategy parent can outlive the window and go on emitting child slices
     * whose ids are derived from it. The ids at risk are the children's, and a
     * child goes terminal long before its parent does - so loading only what is
     * still working would miss exactly the ones that can be regenerated.
     */
    @Test
    void everyOrderInAWorkingTreeIsLoadedNoMatterHowOldItIs() {
        UUID filledChildOfALiveParent = UUID.randomUUID();
        JdbcTemplate jdbc = jdbcReturning(Map.of("root_order_id", List.of(filledChildOfALiveParent)));
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001);

        long loaded = new DedupIndexLoader(jdbc).load(index, WINDOW);

        assertThat(loaded).isEqualTo(1);
        assertThat(index.definitelyNew(filledChildOfALiveParent)).isFalse();
    }

    @Test
    void anEmptyWindowLoadsNothingAndLeavesTheIndexUntouched() {
        JdbcTemplate jdbc = jdbcReturning(Map.of());
        CommandDedupIndex index = new CommandDedupIndex(1_000, 0.001);

        long loaded = new DedupIndexLoader(jdbc).load(index, WINDOW);

        assertThat(loaded).isZero();
        assertThat(index.definitelyNew(UUID.randomUUID())).isTrue();
    }

    /**
     * Drives the loader's RowCallbackHandler over a fixed set of ids per query,
     * standing in for the streaming reads. Queries are matched by a fragment of
     * their SQL so each arm can return its own rows, which is what lets a test
     * assert that a given arm ran at all. Verifies the loader consumes rows one
     * at a time rather than collecting them, which is the point of the streaming
     * shape.
     */
    private static JdbcTemplate jdbcReturning(Map<String, List<UUID>> rowsByQueryFragment) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Answer<Void> stream = invocation -> {
            String sql = invocation.getArgument(0);
            RowCallbackHandler handler = invocation.getArgument(1);
            for (Map.Entry<String, List<UUID>> arm : rowsByQueryFragment.entrySet()) {
                if (!sql.contains(arm.getKey())) continue;
                for (UUID id : arm.getValue()) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getObject(1)).thenReturn(id);
                    handler.processRow(row);
                }
            }
            return null;
        };
        doAnswer(stream).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        return jdbc;
    }
}

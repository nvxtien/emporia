package com.emporia.ordermanagement.disruptor;

public final class HotPathAssertions {
    private static final boolean ENABLED = Boolean.getBoolean("emporia.hotpath.assertions");
    /** {@link HotPathThreadFactory}'s prefix for the ring's writer thread(s). */
    private static final String WRITER_THREAD_PREFIX = "oms-hotpath-";

    private HotPathAssertions() {
    }

    public static void require(boolean condition, String message) {
        if (ENABLED && !condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Whether the current thread is the OMS ring's writer thread, or the one
     * documented exception: {@code main}, where
     * {@code OrderCommandReplayHarness} replays the WAL during
     * {@code DisruptorOrderPipeline.start()}, before the writer thread exists
     * (LMAX_ARCHITECTURE_REWORK_PLAN.md task 5.5's runtime guard for R1,
     * Single Writer ownership).
     *
     * <p>Matched by thread-name prefix, not by comparing against a captured
     * {@code Thread} reference. The two BLP handlers this guards would
     * otherwise need a back-reference to {@code DisruptorOrderPipeline},
     * which already depends on them - a real circular bean dependency, not a
     * hypothetical one (this session hit exactly that class of bug once
     * already, with {@code RoutingExecutionVenueGateway}). Name-prefix
     * matching has a real, accepted gap: it cannot catch a hypothetical
     * caller thread that happens to share one of these two names. It does
     * catch what actually matters - a Tomcat request thread, a
     * {@code @Scheduled} task thread, or any other production thread, all of
     * which have their own recognisable names, none matching either pattern.
     */
    public static boolean isWriterThreadOrStartupReplay() {
        String name = Thread.currentThread().getName();
        return name.startsWith(WRITER_THREAD_PREFIX) || name.equals("main");
    }
}
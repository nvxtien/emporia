package com.emporia.ordermanagement.disruptor;

public final class HotPathAssertions {
    private static final boolean ENABLED = Boolean.getBoolean("emporia.hotpath.assertions");

    private HotPathAssertions() {
    }

    public static void require(boolean condition, String message) {
        if (ENABLED && !condition) {
            throw new IllegalStateException(message);
        }
    }
}
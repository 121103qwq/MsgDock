package com.xgy.lansms;

/** Pure lifecycle decisions kept separate so restart/stop semantics can be unit-tested. */
public final class ReceiverLifecyclePolicy {
    private ReceiverLifecyclePolicy() {}

    public static boolean isExplicitStop(String action) {
        return ReceiverService.ACTION_STOP.equals(action);
    }

    public static boolean shouldPersistEnabled(String action) {
        return action != null && !isExplicitStop(action);
    }

    public static boolean shouldStaySticky(String action) {
        return !isExplicitStop(action);
    }
}

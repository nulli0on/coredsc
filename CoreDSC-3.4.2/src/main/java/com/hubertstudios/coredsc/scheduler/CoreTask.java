package com.hubertstudios.coredsc.scheduler;

/** Cancellable task handle independent of the active server scheduler. */
@FunctionalInterface
public interface CoreTask {
    void cancel();

    static CoreTask noop() {
        return () -> { };
    }
}

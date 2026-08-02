package com.hubertstudios.coredsc.scheduler;


@FunctionalInterface
public interface CoreTask {
    void cancel();

    static CoreTask noop() {
        return () -> { };
    }
}

package com.hubertstudios.coredsc.module;

/** Lifecycle contract for an independently configurable CoreDSC feature. */
public interface CoreModule {
    String id();

    void enable();

    void disable();

    default String statusDetail() {
        return "running";
    }
}

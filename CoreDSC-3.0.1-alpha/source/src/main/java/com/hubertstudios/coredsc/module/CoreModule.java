package com.hubertstudios.coredsc.module;


public interface CoreModule {
    String id();

    void enable();

    void disable();

    default String statusDetail() {
        return "running";
    }
}

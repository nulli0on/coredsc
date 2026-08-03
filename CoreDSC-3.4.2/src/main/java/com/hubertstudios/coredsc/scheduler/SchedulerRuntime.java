package com.hubertstudios.coredsc.scheduler;

/** Runtime selected for CoreDSC task ownership. */
public enum SchedulerRuntime {
    SPIGOT,
    PAPER,
    FOLIA;

    public boolean regionized() {
        return this == FOLIA;
    }
}

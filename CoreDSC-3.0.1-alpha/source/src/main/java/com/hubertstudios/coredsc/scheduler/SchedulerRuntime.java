package com.hubertstudios.coredsc.scheduler;


public enum SchedulerRuntime {
    SPIGOT,
    PAPER,
    FOLIA;

    public boolean regionized() {
        return this == FOLIA;
    }
}

package com.hubertstudios.coredsc.scheduler;

import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;


public final class CoreSchedulers {
    private static final String PAPER_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";

    private CoreSchedulers() { }

    public static CoreScheduler create(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (!classAvailable(PAPER_SCHEDULER)) {
            plugin.getLogger().warning("Paper region scheduler API is unavailable; CoreDSC is using the "
                    + "Spigot compatibility scheduler. Folia safety is unavailable on this runtime.");
            return new PaperCoreScheduler(plugin);
        }

        
        
        try {
            Class<?> type = Class.forName(
                    "com.hubertstudios.coredsc.scheduler.PaperFoliaCoreScheduler",
                    true,
                    CoreSchedulers.class.getClassLoader());
            return (CoreScheduler) type.getConstructor(JavaPlugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : error;
            throw new IllegalStateException("Paper/Folia scheduler initialisation failed", cause);
        }
    }

    private static boolean classAvailable(String name) {
        try {
            Class.forName(name, false, CoreSchedulers.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}

package com.hubertstudios.coredsc.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

 
                               
  
                                                                              
                                                                           
                                                                            
               
   
public interface CoreScheduler {
    boolean isGlobalThread();

    void runGlobal(Runnable task);

    <T> CompletableFuture<T> callGlobal(Supplier<T> supplier);

    CoreTask runGlobalLater(Runnable task, long delayTicks);

    CoreTask runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    CoreTask runAsync(Runnable task);

    CoreTask runForEntity(Entity entity, Runnable task);

    CoreTask runAtLocation(Location location, Runnable task);

    void shutdown();
}

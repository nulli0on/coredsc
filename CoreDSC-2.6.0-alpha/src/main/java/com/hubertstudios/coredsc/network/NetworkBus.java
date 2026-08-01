package com.hubertstudios.coredsc.network;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

                                                                           
public interface NetworkBus extends AutoCloseable {
    CompletableFuture<Void> publish(String eventType, Map<String, String> data);
    void subscribe(BiConsumer<String, Map<String, String>> listener);
    CompletableFuture<Void> put(String key, String value, long ttlSeconds);
    CompletableFuture<Optional<String>> get(String key);
    CompletableFuture<Void> delete(String key);
    boolean isConnected();
    @Override void close();
}

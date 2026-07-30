package com.hubertstudios.coredsc.service;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Optional, reflection-based PlaceholderAPI integration. */
public final class PlaceholderService {
    private final CoreDSCPlugin plugin;
    private final boolean available;
    private final Method setPlaceholders;
    private final AtomicLong lastWarning = new AtomicLong();

    private PlaceholderService(CoreDSCPlugin plugin, boolean available, Method setPlaceholders) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.available = available;
        this.setPlaceholders = setPlaceholders;
    }

    public static PlaceholderService disabled(CoreDSCPlugin plugin) {
        return new PlaceholderService(plugin, false, null);
    }

    public static PlaceholderService detect(CoreDSCPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return disabled(plugin);
        }
        try {
            Plugin placeholderApi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
            if (placeholderApi == null) {
                return disabled(plugin);
            }
            Class<?> apiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                    placeholderApi.getClass().getClassLoader());
            Method method = apiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            return new PlaceholderService(plugin, true, method);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("PlaceholderAPI is installed but its public API could not be loaded", exception);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String apply(OfflinePlayer player, String text) {
        if (!available || setPlaceholders == null || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        try {
            Object result = setPlaceholders.invoke(null, player, text);
            return result == null ? text : result.toString();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnRateLimited("PlaceholderAPI expansion failed: " + rootMessage(exception));
            return text;
        }
    }

    private void warnRateLimited(String message) {
        long now = System.currentTimeMillis();
        long previous = lastWarning.get();
        if (now - previous >= 60_000L && lastWarning.compareAndSet(previous, now)) {
            plugin.getLogger().warning("[PlaceholderAPI] " + message);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}

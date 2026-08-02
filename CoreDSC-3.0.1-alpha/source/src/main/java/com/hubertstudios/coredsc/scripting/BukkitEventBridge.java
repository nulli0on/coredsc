package com.hubertstudios.coredsc.scripting;

import com.hubertstudios.coredsc.CoreDSCPlugin;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;










public final class BukkitEventBridge implements Listener {
    private static final Pattern SAFE_EVENT_NAME = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    private static final Pattern SAFE_PROPERTY = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,7}");
    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SAFE_BRIDGE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SAFE_CLASS_NAME = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*){0,31}");
    private static final Set<String> BLOCKED_SEGMENTS = Set.of(
            "class", "getclass", "wait", "notify", "notifyall", "finalize", "clone"
    );
    private static final int MAX_COLLECTION_ITEMS = 50;
    private static final int MAX_MAP_ITEMS = 50;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_STRING_LENGTH = 2_000;
    private static final int MAX_REGISTRATIONS = 100;

    private final CoreDSCPlugin plugin;
    private final BiFunction<String, Map<String, Object>, CompletableFuture<Boolean>> publisher;
    private final List<Registration> registrations = new ArrayList<>();
    private final Map<String, Long> warningTimes = new ConcurrentHashMap<>();
    private int configuredCount;
    private int activeCount;

    public BukkitEventBridge(
            CoreDSCPlugin plugin,
            BiFunction<String, Map<String, Object>, CompletableFuture<Boolean>> publisher
    ) {
        this.plugin = plugin;
        this.publisher = publisher;
    }

    public void registerConfigured() {
        unregisterAll();
        FileConfiguration config = plugin.getAppConfig();
        if (!config.getBoolean("bot.integrations.bukkit-events.enabled", true)) {
            return;
        }

        List<Map<?, ?>> configured = config.getMapList("bot.integrations.bukkit-events.registrations");
        if (configured.size() > MAX_REGISTRATIONS) {
            plugin.getLogger().warning("[Python] Only the first " + MAX_REGISTRATIONS
                    + " Bukkit event bridges will be considered.");
            configured = configured.subList(0, MAX_REGISTRATIONS);
        }
        int index = 0;
        for (Map<?, ?> raw : configured) {
            index++;
            if (!bool(raw.get("enabled"), true)) {
                continue;
            }
            configuredCount++;
            try {
                Registration registration = parse(raw, index);
                register(registration);
                registrations.add(registration);
                activeCount++;
                plugin.getLogger().info("[Python] Registered Bukkit event bridge '"
                        + registration.id() + "' -> " + registration.pythonEvent());
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().warning("[Python] Bukkit event bridge #" + index
                        + " was skipped: " + rootMessage(error));
            }
        }
    }

    public void unregisterAll() {
        HandlerList.unregisterAll(this);
        registrations.clear();
        warningTimes.clear();
        configuredCount = 0;
        activeCount = 0;
    }

    public int configuredCount() {
        return configuredCount;
    }

    public int activeCount() {
        return activeCount;
    }

    private Registration parse(Map<?, ?> raw, int index) {
        String id = nonBlank(text(raw.get("id")), "bridge-" + index);
        boolean enabled = bool(raw.get("enabled"), true);
        String sourcePluginName = text(raw.get("plugin"));
        String eventClassName = text(raw.get("event-class"));
        String pythonEvent = normalizeEventName(text(raw.get("python-event")));
        EventPriority priority = priority(text(raw.get("priority")));
        boolean ignoreCancelled = bool(raw.get("ignore-cancelled"), true);
        boolean includeMetadata = bool(raw.get("include-metadata"), true);

        if (!SAFE_BRIDGE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bridge id must match " + SAFE_BRIDGE_ID.pattern());
        }
        if (sourcePluginName.isBlank() || sourcePluginName.length() > 64
                || sourcePluginName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(id + ": plugin must be a valid name up to 64 characters");
        }
        if (eventClassName.length() > 256 || !SAFE_CLASS_NAME.matcher(eventClassName).matches()) {
            throw new IllegalArgumentException(id + ": event-class must be a valid Java class name");
        }
        if (pythonEvent.isBlank()) {
            throw new IllegalArgumentException(id + ": python-event must match " + SAFE_EVENT_NAME.pattern());
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Object rawFields = raw.get("fields");
        if (rawFields instanceof Map<?, ?> fieldMap) {
            for (Map.Entry<?, ?> entry : fieldMap.entrySet()) {
                String key = text(entry.getKey());
                String property = text(entry.getValue());
                if (!SAFE_FIELD_NAME.matcher(key).matches()) {
                    throw new IllegalArgumentException(id
                            + ": field names must match " + SAFE_FIELD_NAME.pattern());
                }
                if (!SAFE_PROPERTY.matcher(property).matches()) {
                    throw new IllegalArgumentException(id + ": unsafe property path for field " + key);
                }
                for (String segment : property.split("\\.")) {
                    if (BLOCKED_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException(id + ": blocked property segment " + segment);
                    }
                }
                fields.put(key, property);
            }
        }
        if (fields.size() > 50) {
            throw new IllegalArgumentException(id + ": no more than 50 fields are allowed");
        }
        return new Registration(id, enabled, sourcePluginName, eventClassName, pythonEvent,
                priority, ignoreCancelled, includeMetadata, Map.copyOf(fields));
    }

    @SuppressWarnings("unchecked")
    private void register(Registration registration) {
        Plugin sourcePlugin = plugin.getServer().getPluginManager().getPlugin(registration.sourcePlugin());
        if (sourcePlugin == null || !sourcePlugin.isEnabled()) {
            throw new IllegalStateException(registration.id() + ": plugin '"
                    + registration.sourcePlugin() + "' is not enabled");
        }

        Class<?> rawClass;
        try {
            rawClass = Class.forName(registration.eventClass(), false,
                    sourcePlugin.getClass().getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException(registration.id() + ": event class not found", error);
        }
        if (!Event.class.isAssignableFrom(rawClass)) {
            throw new IllegalArgumentException(registration.id() + ": event-class does not extend Bukkit Event");
        }
        Class<? extends Event> eventClass = (Class<? extends Event>) rawClass;
        EventExecutor executor = (ignored, event) -> execute(registration, event);
        plugin.getServer().getPluginManager().registerEvent(
                eventClass,
                this,
                registration.priority(),
                executor,
                plugin,
                registration.ignoreCancelled()
        );
    }

    private void execute(Registration registration, Event event) {
        if (registration.ignoreCancelled() && event instanceof Cancellable cancellable && cancellable.isCancelled()) {
            return;
        }
        Map<String, Object> rawData = new LinkedHashMap<>();
        try {
            if (registration.includeMetadata()) {
                rawData.put("bridge_id", registration.id());
                rawData.put("source_plugin", registration.sourcePlugin());
                rawData.put("event_class", registration.eventClass());
                rawData.put("asynchronous", event.isAsynchronous());
            }
            for (Map.Entry<String, String> field : registration.fields().entrySet()) {
                rawData.put(field.getKey(), readPropertyPath(event, field.getValue()));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            warnRateLimited(registration.id(), "Event bridge '" + registration.id()
                    + "' could not extract its configured fields: " + rootMessage(error));
            return;
        }

        CompletableFuture<Map<String, Object>> serialized;
        try {
            serialized = event.isAsynchronous()
                    ? plugin.callSync(() -> serializeData(rawData))
                    : CompletableFuture.completedFuture(serializeData(rawData));
        } catch (RuntimeException error) {
            warnRateLimited(registration.id(), "Event bridge '" + registration.id()
                    + "' could not serialize its configured fields: " + rootMessage(error));
            return;
        }
        serialized.thenCompose(data -> {
            CompletableFuture<Boolean> published = publisher.apply(
                    registration.pythonEvent(), data);
            return published == null
                    ? CompletableFuture.failedFuture(
                            new IllegalStateException("Event publisher returned no future"))
                    : published;
        }).exceptionally(error -> {
            warnRateLimited(registration.id() + ":publish", "Event bridge '"
                    + registration.id() + "' failed: " + rootMessage(error));
            return false;
        });
    }

    private static Map<String, Object> serializeData(Map<String, Object> rawData) {
        Map<String, Object> safe = new LinkedHashMap<>();
        rawData.forEach((key, value) -> safe.put(key, jsonSafe(value)));
        return Collections.unmodifiableMap(safe);
    }

    private static Object readPropertyPath(Object root, String path) throws ReflectiveOperationException {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            Method method = findAccessor(current.getClass(), segment);
            try {
                current = method.invoke(current);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof ReflectiveOperationException reflective) {
                    throw reflective;
                }
                throw new IllegalStateException("Accessor " + method.getName() + " failed", cause);
            }
        }
        return current;
    }

    private static Method findAccessor(Class<?> type, String segment) throws NoSuchMethodException {
        String capitalized = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
        List<String> candidates = List.of("get" + capitalized, "is" + capitalized);
        for (String candidate : candidates) {
            try {
                Method method = type.getMethod(candidate);
                if (isSafeAccessor(method)) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                
            }
        }
        if (type.isRecord()) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                if (component.getName().equals(segment) && isSafeAccessor(component.getAccessor())) {
                    return component.getAccessor();
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + segment
                + " (only getX/isX and record-component accessors are allowed)");
    }

    private static boolean isSafeAccessor(Method method) {
        return Modifier.isPublic(method.getModifiers())
                && method.getParameterCount() == 0
                && method.getReturnType() != Void.TYPE
                && !Modifier.isStatic(method.getModifiers());
    }

    public static Object jsonSafe(Object value) {
        return jsonSafe(value, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object jsonSafe(Object value, int depth, Set<Object> seen) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof CharSequence sequence) {
            return truncate(sequence.toString(), MAX_STRING_LENGTH);
        }
        if (value instanceof UUID || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof OfflinePlayer player) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("uuid", player.getUniqueId().toString());
            output.put("name", player.getName() == null ? "" : player.getName());
            output.put("online", player.isOnline());
            return output;
        }
        if (value instanceof Entity entity) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("uuid", entity.getUniqueId().toString());
            output.put("type", entity.getType().name());
            output.put("name", entity.getName());
            output.put("world", entity.getWorld().getName());
            return output;
        }
        if (value instanceof World world) {
            return Map.of("uuid", world.getUID().toString(), "name", world.getName());
        }
        if (value instanceof Location location) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("world", location.getWorld() == null ? "" : location.getWorld().getName());
            output.put("x", location.getX());
            output.put("y", location.getY());
            output.put("z", location.getZ());
            output.put("yaw", location.getYaw());
            output.put("pitch", location.getPitch());
            return output;
        }
        if (depth >= MAX_DEPTH || seen.contains(value)) {
            return truncate(String.valueOf(value), MAX_STRING_LENGTH);
        }

        seen.add(value);
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> output = new LinkedHashMap<>();
                int count = 0;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (count++ >= MAX_MAP_ITEMS) break;
                    output.put(truncate(String.valueOf(entry.getKey()), 128),
                            jsonSafe(entry.getValue(), depth + 1, seen));
                }
                return output;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> output = new ArrayList<>();
                int count = 0;
                for (Object item : collection) {
                    if (count++ >= MAX_COLLECTION_ITEMS) break;
                    output.add(jsonSafe(item, depth + 1, seen));
                }
                return output;
            }
            if (value.getClass().isArray()) {
                List<Object> output = new ArrayList<>();
                int length = Math.min(Array.getLength(value), MAX_COLLECTION_ITEMS);
                for (int index = 0; index < length; index++) {
                    output.add(jsonSafe(Array.get(value, index), depth + 1, seen));
                }
                return output;
            }
            return truncate(String.valueOf(value), MAX_STRING_LENGTH);
        } finally {
            seen.remove(value);
        }
    }

    private void warnRateLimited(String key, String message) {
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean shouldLog =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        warningTimes.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous >= 60_000L) {
                shouldLog.set(true);
                return now;
            }
            return previous;
        });
        if (shouldLog.get()) {
            plugin.getLogger().warning("[Python] " + message);
        }
    }

    private static EventPriority priority(String value) {
        try {
            return EventPriority.valueOf(nonBlank(value, "MONITOR").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("priority must be LOWEST, LOW, NORMAL, HIGH, HIGHEST or MONITOR");
        }
    }

    private static String normalizeEventName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return SAFE_EVENT_NAME.matcher(normalized).matches() ? normalized : "";
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool
                : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value == null ? "" : value;
        return value.substring(0, maximum - 3) + "...";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record Registration(
            String id,
            boolean enabled,
            String sourcePlugin,
            String eventClass,
            String pythonEvent,
            EventPriority priority,
            boolean ignoreCancelled,
            boolean includeMetadata,
            Map<String, String> fields
    ) { }
}

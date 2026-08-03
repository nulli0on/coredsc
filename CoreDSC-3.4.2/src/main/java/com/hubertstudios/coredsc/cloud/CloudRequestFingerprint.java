package com.hubertstudios.coredsc.cloud;

import com.hubertstudios.coredsc.scripting.MiniJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic local binding between an idempotency key and its audited request. */
final class CloudRequestFingerprint {
    private CloudRequestFingerprint() { }

    static String calculate(String operation, Map<String, Object> payload, String reason) {
        String normalizedOperation = Objects.requireNonNull(operation, "operation");
        String normalizedReason = Objects.requireNonNull(reason, "reason");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("operation", normalizedOperation);
        envelope.put("payload", canonicalize(Objects.requireNonNull(payload, "payload"), 0, new int[]{0}));
        envelope.put("reason", normalizedReason);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MiniJson.write(envelope).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object canonicalize(Object value, int depth, int[] nodes) {
        if (depth > 16 || ++nodes[0] > 10_000) {
            throw new IllegalArgumentException("Cloud request is too deeply nested or complex");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> canonical = new ArrayList<>(list.size());
            for (Object item : list) canonical.add(canonicalize(item, depth + 1, nodes));
            return java.util.Collections.unmodifiableList(canonical);
        }
        if (value instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>(map.size());
            for (Object key : map.keySet()) {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("Cloud request object keys must be strings");
                }
                keys.add(text);
            }
            keys.sort(String::compareTo);
            Map<String, Object> canonical = new LinkedHashMap<>();
            for (String key : keys) {
                canonical.put(key, canonicalize(map.get(key), depth + 1, nodes));
            }
            return java.util.Collections.unmodifiableMap(canonical);
        }
        throw new IllegalArgumentException("Cloud request contains an unsupported value type");
    }
}

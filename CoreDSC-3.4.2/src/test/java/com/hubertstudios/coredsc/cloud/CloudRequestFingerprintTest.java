package com.hubertstudios.coredsc.cloud;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class CloudRequestFingerprintTest {
    @Test
    void objectKeyOrderDoesNotChangeTheFingerprint() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("target", "Steve");
        first.put("details", Map.of("duration", "7d", "silent", false));
        first.put("nullable", null);
        first.put("list", java.util.Arrays.asList("a", null, "b"));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("nullable", null);
        second.put("list", java.util.Arrays.asList("a", null, "b"));
        second.put("details", Map.of("silent", false, "duration", "7d"));
        second.put("target", "Steve");

        assertEquals(
                CloudRequestFingerprint.calculate("moderation.ban", first, "Reviewed sanction"),
                CloudRequestFingerprint.calculate("moderation.ban", second, "Reviewed sanction"));
    }

    @Test
    void operationPayloadListOrderAndReasonRemainBoundToTheKey() {
        String baseline = CloudRequestFingerprint.calculate(
                "network.template.apply", Map.of("targets", List.of("a", "b")), "Roll out baseline");

        assertNotEquals(baseline, CloudRequestFingerprint.calculate(
                "network.template.validate", Map.of("targets", List.of("a", "b")), "Roll out baseline"));
        assertNotEquals(baseline, CloudRequestFingerprint.calculate(
                "network.template.apply", Map.of("targets", List.of("b", "a")), "Roll out baseline"));
        assertNotEquals(baseline, CloudRequestFingerprint.calculate(
                "network.template.apply", Map.of("targets", List.of("a", "b")), "Different reason"));
    }
}

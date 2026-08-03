package com.hubertstudios.coredsc.scripting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiniJsonTest {
    @Test
    void roundTripsNestedUnicodeAndEscapedValues() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "CoreDSC 😀\nready");
        value.put("count", 3L);
        value.put("flags", java.util.Arrays.asList(true, false, null));

        assertEquals(value, MiniJson.parseObject(MiniJson.write(value)));
    }

    @Test
    void rejectsDuplicateKeysControlCharactersAndNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"role\":\"OWNER\",\"role\":\"VIEWER\"}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"line\":\"first\nsecond\"}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("1e9999"));
    }

    @Test
    void rejectsExcessiveNestingBeforeTheJvmStackIsAtRisk() {
        String json = "[".repeat(MiniJson.MAXIMUM_NESTING_DEPTH + 2)
                + "0"
                + "]".repeat(MiniJson.MAXIMUM_NESTING_DEPTH + 2);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(json));
        assertTrue(error.getMessage().contains("nesting-depth"));
    }

    @Test
    void rejectsCyclicValuesAndDuplicateSerializedKeys() {
        ArrayList<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> MiniJson.write(cycle));

        Map<Object, Object> duplicateKeys = new LinkedHashMap<>();
        duplicateKeys.put(1, "number");
        duplicateKeys.put("1", "string");
        assertThrows(IllegalArgumentException.class, () -> MiniJson.write(duplicateKeys));
    }

    @Test
    void rejectsUnpairedSurrogatesOnInputAndOutput() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("\"\\ud800\""));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.write("\ud800"));
    }
}

package com.hubertstudios.coredsc.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CloudProtocolLimitsTest {
    @Test
    void measuresUtf8BytesInsteadOfUtf16Characters() {
        assertEquals(3, CloudProtocolLimits.utf8Length("abc"));
        assertEquals(4, CloudProtocolLimits.utf8Length("😀"));
        assertEquals(6, CloudProtocolLimits.utf8Length("€€"));
    }

    @Test
    void acceptsExactLimitAndRejectsOneByteMore() {
        assertFalse(CloudProtocolLimits.exceedsMessageLimit(
                "a".repeat(CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES)));
        assertTrue(CloudProtocolLimits.exceedsMessageLimit(
                "a".repeat(CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES + 1)));
    }

    @Test
    void rejectsMultibytePayloadThatLooksSmallByCharacterCount() {
        int characters = CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES / 4 + 1;
        String emojis = "😀".repeat(characters);

        assertTrue(emojis.length() < CloudProtocolLimits.MAXIMUM_MESSAGE_BYTES);
        assertTrue(CloudProtocolLimits.exceedsMessageLimit(emojis));
    }

    @Test
    void countsUnpairedSurrogatesLikeTheJavaUtf8Encoder() {
        String malformed = "x" + '\ud800' + "y";
        assertEquals(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                CloudProtocolLimits.utf8Length(malformed));
    }
}

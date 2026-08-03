package com.hubertstudios.coredsc.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CloudTextFrameAssemblerTest {
    @Test
    void reassemblesFragmentsIncludingASurrogatePairSplitAcrossFrames() {
        CloudTextFrameAssembler assembler = new CloudTextFrameAssembler(64);
        String emoji = "😀";

        assertNull(assembler.append("{\"emoji\":\"" + emoji.charAt(0), false));
        assertEquals("{\"emoji\":\"😀\"}", assembler.append(emoji.substring(1) + "\"}", true));
        assertEquals(0, assembler.bufferedCharacters());
    }

    @Test
    void resetPreventsFragmentsFromLeakingAcrossConnections() {
        CloudTextFrameAssembler assembler = new CloudTextFrameAssembler(64);

        assertNull(assembler.append("stale-", false));
        assembler.reset();

        assertEquals("fresh", assembler.append("fresh", true));
    }

    @Test
    void acceptsTheExactUtf8LimitAndResetsAfterAnOversizedMessage() {
        CloudTextFrameAssembler assembler = new CloudTextFrameAssembler(4);

        assertEquals("😀", assembler.append("😀", true));
        assertThrows(CloudTextFrameAssembler.MessageTooLargeException.class,
                () -> assembler.append("abcde", true));
        assertEquals(0, assembler.bufferedCharacters());
        assertEquals("ok", assembler.append("ok", true));
    }
}

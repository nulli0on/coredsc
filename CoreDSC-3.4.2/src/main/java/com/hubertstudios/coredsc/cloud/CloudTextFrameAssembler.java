package com.hubertstudios.coredsc.cloud;

import java.util.Objects;

/** Reassembles bounded WebSocket text fragments without quadratic UTF-8 rescans. */
final class CloudTextFrameAssembler {
    static final class MessageTooLargeException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private MessageTooLargeException() {
            super("Cloud message exceeds the UTF-8 byte limit");
        }
    }

    private final int maximumBytes;
    private final StringBuilder text = new StringBuilder();
    private int utf8Bytes;
    private char pendingHighSurrogate;

    CloudTextFrameAssembler(int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        this.maximumBytes = maximumBytes;
    }

    synchronized String append(CharSequence data, boolean last) {
        Objects.requireNonNull(data, "data");
        long candidateBytes = utf8Bytes;
        char candidateHighSurrogate = pendingHighSurrogate;

        for (int index = 0; index < data.length(); index++) {
            char character = data.charAt(index);
            if (candidateHighSurrogate != 0) {
                if (Character.isLowSurrogate(character)) {
                    candidateBytes += 4;
                    candidateHighSurrogate = 0;
                    if (candidateBytes > maximumBytes) return rejectOversized();
                    continue;
                }
                candidateBytes++;
                candidateHighSurrogate = 0;
            }

            if (Character.isHighSurrogate(character)) {
                candidateHighSurrogate = character;
            } else if (Character.isLowSurrogate(character)) {
                candidateBytes++;
            } else if (character <= 0x7f) {
                candidateBytes++;
            } else if (character <= 0x7ff) {
                candidateBytes += 2;
            } else {
                candidateBytes += 3;
            }
            if (candidateBytes > maximumBytes) return rejectOversized();
        }

        if (last && candidateHighSurrogate != 0) {
            candidateBytes++;
            candidateHighSurrogate = 0;
            if (candidateBytes > maximumBytes) return rejectOversized();
        }

        text.append(data);
        utf8Bytes = (int) candidateBytes;
        pendingHighSurrogate = candidateHighSurrogate;
        if (!last) return null;

        String complete = text.toString();
        reset();
        return complete;
    }

    synchronized void reset() {
        text.setLength(0);
        utf8Bytes = 0;
        pendingHighSurrogate = 0;
    }

    synchronized int bufferedCharacters() {
        return text.length();
    }

    private String rejectOversized() {
        reset();
        throw new MessageTooLargeException();
    }
}

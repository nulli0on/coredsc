package com.hubertstudios.coredsc.cloud;

import java.util.Objects;

/** Byte-oriented protocol limits shared by inbound and outbound cloud messages. */
final class CloudProtocolLimits {
    static final int MAXIMUM_MESSAGE_BYTES = 1_048_576;

    private CloudProtocolLimits() { }

    static int utf8Length(CharSequence value) {
        Objects.requireNonNull(value, "value");
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4L;
                index++;
            } else if (Character.isSurrogate(current)) {
                // Java's UTF-8 encoder replaces an unpaired surrogate with one ASCII '?'.
                bytes++;
            } else {
                bytes += 3L;
            }
            if (bytes > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) bytes;
    }

    static boolean exceedsMessageLimit(CharSequence value) {
        Objects.requireNonNull(value, "value");
        long bytes = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) {
                bytes++;
            } else if (current <= 0x7ff) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4L;
                index++;
            } else if (Character.isSurrogate(current)) {
                bytes++;
            } else {
                bytes += 3L;
            }
            if (bytes > MAXIMUM_MESSAGE_BYTES) return true;
        }
        return false;
    }
}

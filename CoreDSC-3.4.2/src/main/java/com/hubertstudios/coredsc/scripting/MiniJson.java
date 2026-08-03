package com.hubertstudios.coredsc.scripting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small dependency-free JSON codec for trusted local state and bounded protocols. */
public final class MiniJson {
    static final int MAXIMUM_NESTING_DEPTH = 64;
    static final int MAXIMUM_VALUES = 100_000;
    static final int MAXIMUM_STRING_CHARACTERS = 1_048_576;

    private MiniJson() { }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        new Writer(out).append(value, 0);
        return out.toString();
    }

    public static Object parse(String json) {
        Parser parser = new Parser(json == null ? "" : json);
        Object value = parser.value(0);
        parser.whitespace();
        if (!parser.end()) {
            throw new IllegalArgumentException("Unexpected JSON data at position " + parser.position());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static void validateString(String value) {
        if (value.length() > MAXIMUM_STRING_CHARACTERS) {
            throw new IllegalArgumentException("JSON string exceeds the character limit");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("JSON string contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("JSON string contains an unpaired surrogate");
            }
        }
    }

    private static void quote(StringBuilder out, String value) {
        validateString(value);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Writer {
        private final StringBuilder out;
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
        private int values;

        private Writer(StringBuilder out) {
            this.out = out;
        }

        private void append(Object value, int depth) {
            if (depth > MAXIMUM_NESTING_DEPTH) {
                throw new IllegalArgumentException("JSON exceeds the nesting-depth limit");
            }
            if (++values > MAXIMUM_VALUES) {
                throw new IllegalArgumentException("JSON exceeds the value-count limit");
            }

            if (value == null) {
                out.append("null");
            } else if (value instanceof String text) {
                quote(out, text);
            } else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                out.append(value);
            } else if (value instanceof Float number) {
                if (!Float.isFinite(number)) throw new IllegalArgumentException("Non-finite JSON number");
                out.append(number);
            } else if (value instanceof Double number) {
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite JSON number");
                out.append(number);
            } else if (value instanceof Map<?, ?> map) {
                enterContainer(value);
                try {
                    out.append('{');
                    boolean first = true;
                    Set<String> serializedKeys = new HashSet<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!first) out.append(',');
                        first = false;
                        String key = String.valueOf(entry.getKey());
                        if (!serializedKeys.add(key)) {
                            throw new IllegalArgumentException("JSON object contains duplicate serialized keys");
                        }
                        quote(out, key);
                        out.append(':');
                        append(entry.getValue(), depth + 1);
                    }
                    out.append('}');
                } finally {
                    leaveContainer(value);
                }
            } else if (value instanceof Iterable<?> iterable) {
                enterContainer(value);
                try {
                    out.append('[');
                    boolean first = true;
                    for (Object item : iterable) {
                        if (!first) out.append(',');
                        first = false;
                        append(item, depth + 1);
                    }
                    out.append(']');
                } finally {
                    leaveContainer(value);
                }
            } else if (value.getClass().isArray()) {
                enterContainer(value);
                try {
                    out.append('[');
                    int length = java.lang.reflect.Array.getLength(value);
                    for (int index = 0; index < length; index++) {
                        if (index > 0) out.append(',');
                        append(java.lang.reflect.Array.get(value, index), depth + 1);
                    }
                    out.append(']');
                } finally {
                    leaveContainer(value);
                }
            } else {
                quote(out, String.valueOf(value));
            }
        }

        private void enterContainer(Object value) {
            if (ancestors.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Cyclic values cannot be encoded as JSON");
            }
        }

        private void leaveContainer(Object value) {
            ancestors.remove(value);
        }
    }

    private static final class Parser {
        private final String input;
        private int index;
        private int values;

        private Parser(String input) {
            this.input = input;
        }

        private Object value(int depth) {
            if (depth > MAXIMUM_NESTING_DEPTH) throw error("JSON exceeds the nesting-depth limit");
            if (++values > MAXIMUM_VALUES) throw error("JSON exceeds the value-count limit");
            whitespace();
            if (end()) throw error("Unexpected end of JSON");
            return switch (input.charAt(index)) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return result;
            while (true) {
                whitespace();
                if (end() || input.charAt(index) != '"') throw error("Expected object key");
                String key = string();
                if (result.containsKey(key)) throw error("Duplicate object key");
                whitespace();
                expect(':');
                result.put(key, value(depth + 1));
                whitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(value(depth + 1));
                whitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    String result = out.toString();
                    try {
                        validateString(result);
                    } catch (IllegalArgumentException error) {
                        throw error(error.getMessage());
                    }
                    return result;
                }
                if (character < 0x20) throw error("Unescaped control character in string");
                if (character != '\\') {
                    appendCharacter(out, character);
                    continue;
                }
                if (end()) throw error("Incomplete escape sequence");
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> appendCharacter(out, escape);
                    case 'b' -> appendCharacter(out, '\b');
                    case 'f' -> appendCharacter(out, '\f');
                    case 'n' -> appendCharacter(out, '\n');
                    case 'r' -> appendCharacter(out, '\r');
                    case 't' -> appendCharacter(out, '\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) throw error("Incomplete unicode escape");
                        String hex = input.substring(index, index + 4);
                        try {
                            appendCharacter(out, (char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException exception) {
                            throw error("Invalid unicode escape");
                        }
                        index += 4;
                    }
                    default -> throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private void appendCharacter(StringBuilder out, char character) {
            if (out.length() >= MAXIMUM_STRING_CHARACTERS) {
                throw error("JSON string exceeds the character limit");
            }
            out.append(character);
        }

        private Object number() {
            int start = index;
            if (consume('-')) { /* sign */ }
            if (consume('0')) {
                // zero cannot be followed by another integer digit
            } else {
                digits();
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                digits();
            }
            if (!end() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!end() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                digits();
            }
            if (start == index) throw error("Expected JSON value");
            String token = input.substring(start, index);
            try {
                if (!decimal) return Long.parseLong(token);
                double number = Double.parseDouble(token);
                if (!Double.isFinite(number)) throw error("Non-finite JSON number");
                return number;
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private void digits() {
            int start = index;
            while (!end() && input.charAt(index) >= '0' && input.charAt(index) <= '9') index++;
            if (start == index) throw error("Expected digit");
        }

        private Object literal(String expected, Object value) {
            if (!input.startsWith(expected, index)) throw error("Invalid literal");
            index += expected.length();
            return value;
        }

        private boolean consume(char expected) {
            if (!end() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw error("Expected '" + expected + "'");
        }

        private void whitespace() {
            while (!end()) {
                char character = input.charAt(index);
                if (character == ' ' || character == '\n' || character == '\r' || character == '\t') index++;
                else break;
            }
        }

        private boolean end() { return index >= input.length(); }
        private int position() { return index; }
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + index);
        }
    }
}

package com.hubertstudios.coredsc.scripting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON codec for the local Python worker protocol. */
public final class MiniJson {
    private MiniJson() { }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder(256);
        append(out, value);
        return out.toString();
    }

    public static Object parse(String json) {
        Parser parser = new Parser(json == null ? "" : json);
        Object value = parser.value();
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

    private static void append(StringBuilder out, Object value) {
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
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                append(out, java.lang.reflect.Array.get(value, i));
            }
            out.append(']');
        } else {
            quote(out, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object value() {
            whitespace();
            if (end()) throw error("Unexpected end of JSON");
            return switch (input.charAt(index)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (consume('}')) return result;
            while (true) {
                whitespace();
                if (end() || input.charAt(index) != '"') throw error("Expected object key");
                String key = string();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            ArrayList<Object> result = new ArrayList<>();
            whitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(value());
                whitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = input.charAt(index++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (end()) throw error("Incomplete escape sequence");
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) throw error("Incomplete unicode escape");
                        String hex = input.substring(index, index + 4);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
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
                return decimal ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException exception) {
                throw error("Invalid number");
            }
        }

        private void digits() {
            int start = index;
            while (!end() && Character.isDigit(input.charAt(index))) index++;
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
                char c = input.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
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

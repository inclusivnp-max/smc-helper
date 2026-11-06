package app.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) throws IOException {
        Parser parser = new Parser(text == null ? "" : text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.end()) {
            throw parser.error("Unexpected trailing JSON content");
        }
        return value;
    }

    public static List<?> asList(Object value) throws IOException {
        if (value instanceof List<?> list) return list;
        throw new IOException("Expected JSON array, got " + typeName(value));
    }

    public static Map<String, Object> asObject(Object value) throws IOException {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
            }
            return result;
        }
        throw new IOException("Expected JSON object, got " + typeName(value));
    }

    public static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    public static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() instanceof String key) result.put(key, entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    public static String string(Object value, String fallback) {
        if (value instanceof String text) return text;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return fallback;
    }

    public static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static long integer(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        return fallback;
    }

    public static String snippet(String text) {
        String normalized = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean end() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!end() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        Object parseValue() throws IOException {
            skipWhitespace();
            if (end()) throw error("Unexpected end of JSON");
            char ch = text.charAt(pos);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (ch == '-' || Character.isDigit(ch)) return parseNumber();
            if (match("true")) return Boolean.TRUE;
            if (match("false")) return Boolean.FALSE;
            if (match("null")) return null;
            throw error("Unexpected JSON token '" + ch + "'");
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (end() || text.charAt(pos) != '"') throw error("Expected JSON object key");
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!end()) {
                char ch = text.charAt(pos++);
                if (ch == '"') return sb.toString();
                if (ch != '\\') {
                    sb.append(ch);
                    continue;
                }
                if (end()) throw error("Unterminated JSON escape");
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(parseUnicode());
                    default -> throw error("Unsupported JSON escape '\\" + esc + "'");
                }
            }
            throw error("Unterminated JSON string");
        }

        private char parseUnicode() throws IOException {
            if (pos + 4 > text.length()) throw error("Invalid unicode escape");
            String hex = text.substring(pos, pos + 4);
            pos += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException ex) {
                throw error("Invalid unicode escape");
            }
        }

        private Number parseNumber() throws IOException {
            int start = pos;
            if (peek('-')) pos++;
            readDigits();
            boolean floating = false;
            if (peek('.')) {
                floating = true;
                pos++;
                readDigits();
            }
            if (!end() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                floating = true;
                pos++;
                if (!end() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                readDigits();
            }
            String raw = text.substring(start, pos);
            try {
                return floating ? Double.parseDouble(raw) : Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                try {
                    return Double.parseDouble(raw);
                } catch (NumberFormatException ignored) {
                    throw error("Invalid JSON number");
                }
            }
        }

        private void readDigits() throws IOException {
            int start = pos;
            while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            if (start == pos) throw error("Expected digit");
        }

        private boolean match(String literal) {
            if (!text.startsWith(literal, pos)) return false;
            pos += literal.length();
            return true;
        }

        private boolean peek(char expected) {
            return !end() && text.charAt(pos) == expected;
        }

        private void expect(char expected) throws IOException {
            if (end() || text.charAt(pos) != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        IOException error(String message) {
            return new IOException(message + " at position " + pos);
        }
    }
}

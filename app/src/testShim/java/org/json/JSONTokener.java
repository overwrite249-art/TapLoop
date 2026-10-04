package org.json;

public class JSONTokener {
    private final String in;
    private int pos;

    public JSONTokener(String in) {
        if (in != null && in.startsWith("\ufeff")) in = in.substring(1);
        this.in = in;
    }

    public Object nextValue() throws JSONException {
        int c = nextClean();
        switch (c) {
            case -1: throw syntaxError("End of input");
            case '{': return readObject();
            case '[': return readArray();
            case '\'':
            case '"': return nextString((char) c);
            default:
                pos--;
                return readLiteral();
        }
    }

    private int nextClean() {
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            return c;
        }
        return -1;
    }

    public String nextString(char quote) throws JSONException {
        StringBuilder b = new StringBuilder();
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c == quote) return b.toString();
            if (c == '\\') {
                if (pos >= in.length()) throw syntaxError("Unterminated escape");
                char e = in.charAt(pos++);
                switch (e) {
                    case 'u':
                        if (pos + 4 > in.length()) throw syntaxError("Unterminated escape");
                        try {
                            b.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw syntaxError("Invalid escape");
                        }
                        pos += 4;
                        break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 'f': b.append('\f'); break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        throw syntaxError("Unterminated string");
    }

    private Object readLiteral() throws JSONException {
        int start = pos;
        while (pos < in.length() && "{}[]/\\:,=;# \t\f\r\n".indexOf(in.charAt(pos)) == -1) pos++;
        String s = in.substring(start, pos);
        if (s.isEmpty()) throw syntaxError("Expected literal value");
        if ("null".equalsIgnoreCase(s)) return JSONObject.NULL;
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        if (s.indexOf('.') == -1 && s.indexOf('e') == -1 && s.indexOf('E') == -1) {
            try {
                long l = Long.parseLong(s);
                if (l <= Integer.MAX_VALUE && l >= Integer.MIN_VALUE) return (int) l;
                return l;
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException ignored) {
        }
        return s;
    }

    private JSONObject readObject() throws JSONException {
        JSONObject o = new JSONObject();
        int first = nextClean();
        if (first == '}') return o;
        if (first != -1) pos--;
        while (true) {
            Object name = nextValue();
            if (!(name instanceof String)) {
                if (name == null) throw syntaxError("Names cannot be null");
                throw syntaxError("Names must be strings, but " + name + " is of type " + name.getClass().getName());
            }
            int sep = nextClean();
            if (sep != ':' && sep != '=') throw syntaxError("Expected ':' after " + name);
            if (pos < in.length() && in.charAt(pos) == '>') pos++;
            o.put((String) name, nextValue());
            switch (nextClean()) {
                case '}': return o;
                case ';':
                case ',': continue;
                default: throw syntaxError("Unterminated object");
            }
        }
    }

    private JSONArray readArray() throws JSONException {
        JSONArray a = new JSONArray();
        boolean hasTrailing = false;
        while (true) {
            switch (nextClean()) {
                case -1: throw syntaxError("Unterminated array");
                case ']':
                    if (hasTrailing) a.put(null);
                    return a;
                case ',':
                case ';':
                    a.put(null);
                    hasTrailing = true;
                    continue;
                default:
                    pos--;
            }
            a.put(nextValue());
            switch (nextClean()) {
                case ']': return a;
                case ',':
                case ';':
                    hasTrailing = true;
                    continue;
                default: throw syntaxError("Unterminated array");
            }
        }
    }

    public JSONException syntaxError(String message) {
        return new JSONException(message + " at character " + pos + " of " + in);
    }

    public boolean more() {
        return pos < in.length();
    }

    @Override
    public String toString() {
        return " at character " + pos + " of " + in;
    }
}

package org.json;

// coercion rules, same as Android's org.json
class JSON {
    static double checkDouble(double d) throws JSONException {
        if (Double.isInfinite(d) || Double.isNaN(d)) throw new JSONException("Forbidden numeric value: " + d);
        return d;
    }

    static Boolean toBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = (String) v;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }

    static Double toDouble(Object v) {
        if (v instanceof Double) return (Double) v;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.valueOf((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static Integer toInteger(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return (int) Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static Long toLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return (long) Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    static String toString(Object v) {
        if (v instanceof String) return (String) v;
        if (v != null) return String.valueOf(v);
        return null;
    }

    static JSONException typeMismatch(Object where, Object actual, String type) throws JSONException {
        if (actual == null) throw new JSONException("Value at " + where + " is null.");
        throw new JSONException("Value " + actual + " at " + where + " of type "
                + actual.getClass().getName() + " cannot be converted to " + type);
    }

    static String numberToString(Number n) throws JSONException {
        if (n == null) throw new JSONException("Number must be non-null");
        double d = n.doubleValue();
        checkDouble(d);
        if (n.equals(-0.0d)) return "-0";
        long l = n.longValue();
        if (d == (double) l) return Long.toString(l);
        return n.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '/': b.append("\\/"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c <= 0x1F) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    static void write(StringBuilder b, Object v) throws JSONException {
        if (v == null || v == JSONObject.NULL) b.append("null");
        else if (v instanceof JSONObject) ((JSONObject) v).write(b);
        else if (v instanceof JSONArray) ((JSONArray) v).write(b);
        else if (v instanceof Boolean) b.append(v);
        else if (v instanceof Number) b.append(numberToString((Number) v));
        else quote(b, v.toString());
    }
}

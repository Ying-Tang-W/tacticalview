package tacticalview.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（无第三方依赖，便于核心库保持零依赖）。
 * 结果类型：Map / List / Double / String / Boolean / null。
 */
public final class Json {
    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.p != j.s.length()) {
            throw j.err("Trailing content");
        }
        return v;
    }

    private RuntimeException err(String m) {
        return new IllegalArgumentException("JSON: " + m + " at " + p);
    }

    private boolean peek(char c) {
        return p < s.length() && s.charAt(p) == c;
    }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                p++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (p >= s.length()) {
            throw err("Unexpected end");
        }
        switch (s.charAt(p)) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, p)) {
            throw err("Expected " + lit);
        }
        p += lit.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++;
        ws();
        if (peek('}')) {
            p++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (!peek(':')) {
                throw err("Expected ':'");
            }
            p++;
            ws();
            m.put(k, value());
            ws();
            if (peek(',')) {
                p++;
                continue;
            }
            if (peek('}')) {
                p++;
                return m;
            }
            throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        p++;
        ws();
        if (peek(']')) {
            p++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (peek(',')) {
                p++;
                continue;
            }
            if (peek(']')) {
                p++;
                return l;
            }
            throw err("Expected ',' or ']'");
        }
    }

    private String string() {
        if (!peek('"')) {
            throw err("Expected string");
        }
        p++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (p >= s.length()) {
                throw err("Unterminated string");
            }
            char c = s.charAt(p++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (p >= s.length()) {
                throw err("Bad escape");
            }
            char e = s.charAt(p++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (p + 4 > s.length()) {
                        throw err("Bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default:
                    throw err("Bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) {
            p++;
        }
        if (start == p) {
            throw err("Unexpected character '" + s.charAt(p) + "'");
        }
        return Double.parseDouble(s.substring(start, p));
    }

    // ---------- 取值辅助（对 null 容错，缺失时返回默认值） ----------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static int i(Map<String, Object> m, String k, int def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    public static float f(Map<String, Object> m, String k, float def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String ? (String) v : def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** 读取数字数组；缺失或长度不符时返回 def。 */
    public static float[] floats(Map<String, Object> m, String k, float[] def) {
        List<Object> l = arr(m == null ? null : m.get(k));
        if (l == null || (def != null && l.size() != def.length)) {
            return def;
        }
        float[] r = new float[l.size()];
        for (int idx = 0; idx < r.length; idx++) {
            Object v = l.get(idx);
            r[idx] = v instanceof Number ? ((Number) v).floatValue() : 0f;
        }
        return r;
    }
}

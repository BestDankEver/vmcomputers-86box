package mcvmcomputers.emu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for the 86Box automation replies, the nightly manifest
 * and the parts catalog. Objects become {@code Map<String, Object>}, arrays
 * {@code List<Object>}, numbers {@code Double}, plus String/Boolean/null.
 *
 * Kept dependency-free so the emulator code works the same whatever Gson
 * version Minecraft ships, and can be tested without Minecraft.
 */
public final class Json {
	private final String s;
	private int i;

	private Json(String s) {
		this.s = s;
	}

	public static Object parse(String text) {
		Json p = new Json(text);
		p.ws();
		Object v = p.value();
		p.ws();
		if (p.i != p.s.length()) {
			throw new IllegalArgumentException("Trailing data in JSON at " + p.i);
		}
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object o = parse(text);
		if (!(o instanceof Map)) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		return (Map<String, Object>) o;
	}

	private void ws() {
		while (i < s.length()) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				i++;
			} else {
				break;
			}
		}
	}

	private Object value() {
		if (i >= s.length()) {
			throw new IllegalArgumentException("Unexpected end of JSON");
		}
		char c = s.charAt(i);
		switch (c) {
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

	private void expect(String word) {
		if (!s.startsWith(word, i)) {
			throw new IllegalArgumentException("Bad JSON literal at " + i);
		}
		i += word.length();
	}

	private Map<String, Object> object() {
		Map<String, Object> m = new LinkedHashMap<>();
		i++; // {
		ws();
		if (peek() == '}') {
			i++;
			return m;
		}
		while (true) {
			ws();
			String k = string();
			ws();
			if (peek() != ':') {
				throw new IllegalArgumentException("Expected ':' at " + i);
			}
			i++;
			ws();
			m.put(k, value());
			ws();
			char c = peek();
			i++;
			if (c == '}') {
				return m;
			}
			if (c != ',') {
				throw new IllegalArgumentException("Expected ',' or '}' at " + (i - 1));
			}
		}
	}

	private List<Object> array() {
		List<Object> l = new ArrayList<>();
		i++; // [
		ws();
		if (peek() == ']') {
			i++;
			return l;
		}
		while (true) {
			ws();
			l.add(value());
			ws();
			char c = peek();
			i++;
			if (c == ']') {
				return l;
			}
			if (c != ',') {
				throw new IllegalArgumentException("Expected ',' or ']' at " + (i - 1));
			}
		}
	}

	private char peek() {
		if (i >= s.length()) {
			throw new IllegalArgumentException("Unexpected end of JSON");
		}
		return s.charAt(i);
	}

	private String string() {
		if (peek() != '"') {
			throw new IllegalArgumentException("Expected string at " + i);
		}
		i++;
		StringBuilder b = null;
		int start = i;
		while (true) {
			char c = peek();
			if (c == '"') {
				String out = (b == null) ? s.substring(start, i) : b.append(s, start, i).toString();
				i++;
				return out;
			}
			if (c == '\\') {
				if (b == null) {
					b = new StringBuilder();
				}
				b.append(s, start, i);
				i++;
				char e = peek();
				i++;
				switch (e) {
				case '"': b.append('"'); break;
				case '\\': b.append('\\'); break;
				case '/': b.append('/'); break;
				case 'b': b.append('\b'); break;
				case 'f': b.append('\f'); break;
				case 'n': b.append('\n'); break;
				case 'r': b.append('\r'); break;
				case 't': b.append('\t'); break;
				case 'u':
					b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
					i += 4;
					break;
				default:
					throw new IllegalArgumentException("Bad escape at " + (i - 1));
				}
				start = i;
			} else {
				i++;
			}
		}
	}

	private Double number() {
		int start = i;
		while (i < s.length()) {
			char c = s.charAt(i);
			if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
				i++;
			} else {
				break;
			}
		}
		if (start == i) {
			throw new IllegalArgumentException("Unexpected character '" + s.charAt(i) + "' at " + i);
		}
		return Double.valueOf(s.substring(start, i));
	}

	// ---- Small typed accessors -------------------------------------------------

	public static String str(Map<String, Object> m, String k, String def) {
		Object o = m.get(k);
		return (o instanceof String) ? (String) o : def;
	}

	public static int integer(Map<String, Object> m, String k, int def) {
		Object o = m.get(k);
		return (o instanceof Number) ? ((Number) o).intValue() : def;
	}

	public static long lng(Map<String, Object> m, String k, long def) {
		Object o = m.get(k);
		return (o instanceof Number) ? ((Number) o).longValue() : def;
	}

	public static double dbl(Map<String, Object> m, String k, double def) {
		Object o = m.get(k);
		return (o instanceof Number) ? ((Number) o).doubleValue() : def;
	}

	public static boolean bool(Map<String, Object> m, String k, boolean def) {
		Object o = m.get(k);
		return (o instanceof Boolean) ? (Boolean) o : def;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(Map<String, Object> m, String k) {
		Object o = m.get(k);
		return (o instanceof Map) ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> arr(Map<String, Object> m, String k) {
		Object o = m.get(k);
		return (o instanceof List) ? (List<Object>) o : new ArrayList<>();
	}

	/** Quotes a string for use in JSON output. */
	public static String quote(String v) {
		StringBuilder b = new StringBuilder("\"");
		for (int j = 0; j < v.length(); j++) {
			char c = v.charAt(j);
			switch (c) {
			case '"': b.append("\\\""); break;
			case '\\': b.append("\\\\"); break;
			case '\n': b.append("\\n"); break;
			case '\r': b.append("\\r"); break;
			case '\t': b.append("\\t"); break;
			default:
				if (c < 0x20) {
					b.append(String.format("\\u%04x", (int) c));
				} else {
					b.append(c);
				}
			}
		}
		return b.append('"').toString();
	}
}

package poker.common;

import java.io.IOException;
import java.io.Reader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/** UTF-8 text protocol: TYPE\tkey=urlEncodedValue ... \n. */
public final class Wire {
    public static final int MAX_LINE = 16384;
    private Wire() { }

    public static String message(String type, String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, String> fields = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) fields.put(pairs[i], pairs[i + 1]);
        return message(type, fields);
    }

    public static String message(String type, Map<String, String> fields) {
        StringBuilder b = new StringBuilder(type);
        try {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                b.append('\t').append(e.getKey()).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        if (b.length() > MAX_LINE) throw new IllegalArgumentException("message too long");
        return b.toString();
    }

    public static Map<String, String> parse(String line) {
        if (line == null || line.length() > MAX_LINE) throw new IllegalArgumentException("bad frame");
        String[] parts = line.split("\t", -1);
        if (!parts[0].matches("[A-Z_]{1,20}") || parts.length > 200)
            throw new IllegalArgumentException("bad message type");
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("type", parts[0]);
        try {
            for (int i = 1; i < parts.length; i++) {
                int eq = parts[i].indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("bad field");
                String key = parts[i].substring(0, eq);
                if (!key.matches("[a-zA-Z0-9_.]{1,32}") || fields.containsKey(key))
                    throw new IllegalArgumentException("bad/duplicate key");
                fields.put(key, URLDecoder.decode(parts[i].substring(eq + 1), "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
        return fields;
    }

    /** Bounded framing: one TCP read is not necessarily one message. */
    public static String readLine(Reader in) throws IOException {
        StringBuilder b = new StringBuilder();
        int rawLength = 0;
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return b.toString();
            if (++rawLength > MAX_LINE) throw new IOException("frame exceeds limit");
            if (c == '\r') continue;
            b.append((char) c);
        }
        if (b.length() > 0) throw new IOException("incomplete frame");
        return null;
    }

    public static int number(Map<String, String> m, String key) {
        String value = m.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return Integer.parseInt(value);
    }

    public static String required(Map<String, String> m, String key) {
        String value = m.get(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }
}

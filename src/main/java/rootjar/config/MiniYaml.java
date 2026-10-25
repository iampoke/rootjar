package rootjar.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small parser for the YAML subset config.yml uses: nested mappings, lists of mappings ("- key: v"),
 * inline lists ([a, b]), quoted/plain scalars, integers, booleans and # comments.
 *
 * WHY THIS EXISTS: the spec asks for SnakeYAML, but the build environment for M1 had no network access, so
 * the dependency could not be downloaded. ConfigLoader is the only caller, so swapping this class for
 * SnakeYAML later is a one-line change (see README). Anchors, multi-line strings and flow maps are NOT supported.
 *
 * Returns Map<String,Object>, List<Object>, String, Long, Boolean or null.
 */
public final class MiniYaml {
    private record Line(int no, int indent, String text) {}

    private final List<Line> lines = new ArrayList<>();
    private int pos;

    private MiniYaml() {}

    public static Object parse(String source) throws ConfigException {
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        MiniYaml p = new MiniYaml();
        String[] raw = source.split("\r?\n", -1);
        for (int i = 0; i < raw.length; i++) {
            String s = stripComment(raw[i]).stripTrailing();
            if (s.isBlank() || s.equals("---")) continue;
            int indent = 0;
            while (indent < s.length() && s.charAt(indent) == ' ') indent++;
            if (s.charAt(indent) == '\t') throw err(i + 1, "tabs are not allowed for indentation");
            p.lines.add(new Line(i + 1, indent, s.substring(indent)));
        }
        if (p.lines.isEmpty()) return new LinkedHashMap<String, Object>();
        Object result = p.block(p.lines.get(0).indent());
        if (p.pos < p.lines.size()) throw err(p.lines.get(p.pos).no(), "unexpected indentation or content");
        return result;
    }

    private static boolean isItem(String text) {
        return text.equals("-") || text.startsWith("- ");
    }

    private Object block(int indent) throws ConfigException {
        return isItem(lines.get(pos).text()) ? list(indent) : map(indent);
    }

    private Map<String, Object> map(int indent) throws ConfigException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent() < indent) break;
            if (l.indent() > indent) throw err(l.no(), "unexpected indentation");
            if (isItem(l.text())) break;
            int c = keyEnd(l.text());
            if (c < 0) throw err(l.no(), "expected 'key: value' but found: " + l.text());
            String key = unquoteKey(l.text().substring(0, c).trim());
            String rest = l.text().substring(c + 1).trim();
            pos++;
            Object value;
            if (rest.isEmpty()) {
                // Value is a nested block on the following lines (a list may sit at the same indent as its key).
                if (pos < lines.size() && (lines.get(pos).indent() > indent
                        || (lines.get(pos).indent() == indent && isItem(lines.get(pos).text())))) {
                    value = block(lines.get(pos).indent());
                } else {
                    value = null;
                }
            } else {
                value = scalar(rest, l.no());
            }
            m.put(key, value);
        }
        return m;
    }

    private List<Object> list(int indent) throws ConfigException {
        List<Object> out = new ArrayList<>();
        while (pos < lines.size()) {
            Line l = lines.get(pos);
            if (l.indent() != indent || !isItem(l.text())) break;
            String afterDash = l.text().length() == 1 ? "" : l.text().substring(1);
            String rest = afterDash.stripLeading();
            int textIndent = indent + 1 + (afterDash.length() - rest.length());
            if (rest.isEmpty()) {
                pos++;
                if (pos < lines.size() && lines.get(pos).indent() > indent) out.add(block(lines.get(pos).indent()));
                else out.add(null);
            } else if (!rest.startsWith("[") && keyEnd(rest) >= 0) {
                // "- key: value" starts a mapping whose later keys are indented to line up with this first key.
                lines.set(pos, new Line(l.no(), textIndent, rest));
                out.add(map(textIndent));
            } else {
                pos++;
                out.add(scalar(rest, l.no()));
            }
        }
        return out;
    }

    /** Index of the ':' that ends a key (followed by space or end of line), or -1. */
    private static int keyEnd(String t) {
        char q = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q != 0) {
                if (c == '\\' && q == '"') i++;
                else if (c == q) q = 0;
                continue;
            }
            if ((c == '"' || c == '\'') && i == 0) { q = c; continue; }
            if (c == ':' && (i + 1 == t.length() || t.charAt(i + 1) == ' ')) return i;
        }
        return -1;
    }

    private static String unquoteKey(String k) {
        if (k.length() >= 2 && (k.charAt(0) == '"' || k.charAt(0) == '\'') && k.charAt(k.length() - 1) == k.charAt(0)) {
            return k.substring(1, k.length() - 1);
        }
        return k;
    }

    private static Object scalar(String s, int no) throws ConfigException {
        if (s.startsWith("[")) return flowList(s, no);
        if (s.startsWith("\"") || s.startsWith("'")) return quoted(s, no);
        if (s.equals("~") || s.equals("null")) return null;
        if (s.equals("true")) return Boolean.TRUE;
        if (s.equals("false")) return Boolean.FALSE;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static String quoted(String s, int no) throws ConfigException {
        char q = s.charAt(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q == '"' && c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else if (c == q) {
                if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    sb.append('\'');
                    i++;
                } else {
                    if (i != s.length() - 1) throw err(no, "unexpected text after closing quote");
                    return sb.toString();
                }
            } else {
                sb.append(c);
            }
        }
        throw err(no, "unterminated quoted string");
    }

    private static List<Object> flowList(String s, int no) throws ConfigException {
        if (!s.endsWith("]")) throw err(no, "unterminated [ list ]");
        String inner = s.substring(1, s.length() - 1);
        List<Object> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (q != 0) {
                cur.append(c);
                if (c == '\\' && q == '"' && i + 1 < inner.length()) cur.append(inner.charAt(++i));
                else if (c == q) q = 0;
            } else if (c == '"' || c == '\'') {
                q = c;
                cur.append(c);
            } else if (c == ',') {
                addFlowItem(out, cur, no);
            } else {
                cur.append(c);
            }
        }
        addFlowItem(out, cur, no);
        return out;
    }

    private static void addFlowItem(List<Object> out, StringBuilder cur, int no) throws ConfigException {
        String t = cur.toString().strip();
        cur.setLength(0);
        if (!t.isEmpty()) out.add(scalar(t, no));
    }

    /** Removes a trailing "# comment", ignoring # inside quotes or glued to a word. */
    private static String stripComment(String s) {
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == '\\' && q == '"') i++;
                else if (c == q) q = 0;
                continue;
            }
            char prev = i == 0 ? ' ' : s.charAt(i - 1);
            boolean tokenStart = prev == ' ' || prev == '[' || prev == ',';
            if ((c == '"' || c == '\'') && tokenStart) q = c;
            else if (c == '#' && tokenStart) return s.substring(0, i);
        }
        return s;
    }

    private static ConfigException err(int line, String msg) {
        return new ConfigException("config.yml line " + line + ": " + msg);
    }
}

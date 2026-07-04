package dev.yoshiro.versioneye;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenient version comparison for the loosely formatted version strings found
 * in the wild: handles "v" prefixes, numeric segments of different lengths
 * ("1.9" vs "1.10"), and pre-release qualifiers ("1.0-beta" < "1.0").
 */
final class VersionComparator {

    private VersionComparator() {
    }

    /** Returns a value > 0 if {@code a} is newer than {@code b}. */
    static int compare(String a, String b) {
        List<String> ta = tokenize(a);
        List<String> tb = tokenize(b);
        int len = Math.max(ta.size(), tb.size());
        for (int i = 0; i < len; i++) {
            String x = i < ta.size() ? ta.get(i) : null;
            String y = i < tb.size() ? tb.get(i) : null;
            int c = compareTokens(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static List<String> tokenize(String version) {
        String v = version.strip().toLowerCase(Locale.ROOT);
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        // Build metadata ("1.2.3+build.45") does not affect precedence.
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curDigit = false;
        for (char c : v.toCharArray()) {
            if (c == '.' || c == '-' || c == '_' || c == ' ') {
                flush(tokens, cur);
                curDigit = false;
                continue;
            }
            boolean digit = Character.isDigit(c);
            // Split boundaries like "1.0b2" into "1", "0", "b", "2".
            if (!cur.isEmpty() && digit != curDigit) {
                flush(tokens, cur);
            }
            cur.append(c);
            curDigit = digit;
        }
        flush(tokens, cur);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder cur) {
        if (!cur.isEmpty()) {
            tokens.add(cur.toString());
            cur.setLength(0);
        }
    }

    private static int compareTokens(String x, String y) {
        // A missing segment ranks above a pre-release qualifier ("1.0" > "1.0-rc")
        // but below a numeric segment ("1.0" < "1.0.1").
        if (x == null && y == null) {
            return 0;
        }
        if (x == null) {
            return -rankAgainstMissing(y);
        }
        if (y == null) {
            return rankAgainstMissing(x);
        }
        boolean nx = isNumeric(x);
        boolean ny = isNumeric(y);
        if (nx && ny) {
            return Long.compare(parseLong(x), parseLong(y));
        }
        if (nx != ny) {
            // Numbers rank above qualifiers: "1.0.1" > "1.0.rc".
            return nx ? 1 : -1;
        }
        int qa = qualifierRank(x);
        int qb = qualifierRank(y);
        if (qa != qb) {
            return Integer.compare(qa, qb);
        }
        return x.compareTo(y);
    }

    private static int rankAgainstMissing(String token) {
        if (isNumeric(token)) {
            return parseLong(token) == 0 ? 0 : 1;
        }
        return qualifierRank(token) < 0 ? -1 : 1;
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int qualifierRank(String s) {
        return switch (s) {
            case "snapshot", "dev" -> -5;
            case "alpha", "a" -> -4;
            case "beta", "b" -> -3;
            case "rc", "cr", "pre", "prerelease" -> -2;
            default -> -1;
        };
    }
}

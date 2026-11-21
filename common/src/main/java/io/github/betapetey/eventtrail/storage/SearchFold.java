package io.github.betapetey.eventtrail.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The EventTrail search fold (T31-R4): one letter-case rule for lookup text filters, so that one filter finds
 * the same rows on SQLite, MySQL/MariaDB and PostgreSQL, whatever the server version, collation or locale.
 *
 * <p>fold(x) is the {@code Locale.ROOT} lower case of x with U+0130 first replaced by 'I', after which
 * "i" + U+0307 becomes "i" and U+03C2 becomes U+03C3. No language rules apply: Turkish dotless i is its own
 * letter. Needles are always folded here, in Java.</p>
 *
 * <p>Stored text is matched in one of two ways (dbbugs-01 design-r2, option C):</p>
 * <ul>
 *   <li>An ASCII needle is compared in SQL with {@link #asciiNeedleView(String)} of the stored text: U+0130
 *   becomes 'I' and U+212A 'K', ASCII letters are lower-cased, then "i" + U+0307 becomes "i". For an ASCII
 *   needle this finds exactly the rows {@link #fold(String)} finds: only U+0130 and U+212A fold to ASCII
 *   letters, only U+0307 can disappear next to one, and every other character stays non-ASCII under both.
 *   No Unicode case table is involved, so every backend and server version gives the same rows.</li>
 *   <li>A non-ASCII needle can only match stored text that holds a non-ASCII character. SQL keeps those rows
 *   as candidates and {@link TextCheck} decides them here with {@link #fold(String)}.</li>
 * </ul>
 */
final class SearchFold {
    private SearchFold() {
    }

    static String fold(String value) {
        if (value == null) return null;
        if (isAscii(value)) return value.toLowerCase(Locale.ROOT);
        String lowered = value.replace('\u0130', 'I').toLowerCase(Locale.ROOT);
        return lowered.replace("i\u0307", "i").replace('\u03c2', '\u03c3');
    }

    static boolean isAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) return false;
        }
        return true;
    }

    /** What every backend's SQL compares with an ASCII needle (AbstractJdbcStorage#asciiNeedleFoldSql). */
    static String asciiNeedleView(String value) {
        StringBuilder view = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (unit == '\u0130') unit = 'I';
            else if (unit == '\u212a') unit = 'K';
            view.append(unit >= 'A' && unit <= 'Z' ? (char) (unit + ('a' - 'A')) : unit);
        }
        return view.toString().replace("i\u0307", "i");
    }

    /** One lookup text filter: true when any needle equals (player) or occurs in (other filters) a folded column. */
    record TextPredicate(List<String> columns, List<String> foldedNeedles, boolean equality) {
        TextPredicate {
            columns = List.copyOf(columns);
            foldedNeedles = List.copyOf(foldedNeedles);
        }
    }

    /** The text filters of one lookup that are decided here; a row must pass every one of them. */
    record TextCheck(List<TextPredicate> predicates, List<String> columns) {
        TextCheck {
            predicates = List.copyOf(predicates);
            columns = List.copyOf(columns);
        }

        static TextCheck of(List<TextPredicate> predicates) {
            List<String> columns = new ArrayList<>();
            for (TextPredicate predicate : predicates) {
                for (String column : predicate.columns()) {
                    if (!columns.contains(column)) columns.add(column);
                }
            }
            return new TextCheck(predicates, columns);
        }

        /** {@code storedText} holds the stored values of {@link #columns()}, in that order; null counts as "". */
        boolean matches(List<String> storedText) {
            for (TextPredicate predicate : predicates) {
                if (!matches(predicate, storedText)) return false;
            }
            return true;
        }

        private boolean matches(TextPredicate predicate, List<String> storedText) {
            for (String column : predicate.columns()) {
                String stored = storedText.get(columns.indexOf(column));
                String folded = fold(stored == null ? "" : stored);
                for (String needle : predicate.foldedNeedles()) {
                    if (predicate.equality() ? folded.equals(needle) : folded.contains(needle)) return true;
                }
            }
            return false;
        }
    }

    /** The three SQL forms a backend must run exactly; see {@link #SELF_TESTS}. */
    enum SelfTestForm { EQUALS, CONTAINS, NON_ASCII }

    /** One startup self-test case: stored text, form, folded ASCII needle (if any) and the right answer. */
    record SelfTest(String text, SelfTestForm form, String needle, boolean expected) {
    }

    /**
     * Run by AbstractJdbcStorage#verifySearchTextSql through each backend's SQL. Together they pin exact
     * equality (a trailing space and an accent count), literal '_' and '%', the U+0130, U+212A and U+0307
     * rules, a plain 'I' (a Turkish collation would lower it to dotless i) and 4-byte characters passing
     * through the connection.
     */
    static final List<SelfTest> SELF_TESTS = List.of(
            new SelfTest("\u0130STANBUL Ik\u212ai\u0307", SelfTestForm.EQUALS, "istanbul ikki", true),
            new SelfTest("Elite ", SelfTestForm.EQUALS, "elite", false),
            new SelfTest("\u00c9LITE", SelfTestForm.EQUALS, "elite", false),
            new SelfTest("\ud801\udc00 Mine\u0130TEM_1%", SelfTestForm.CONTAINS, "mineitem_1%", true),
            new SelfTest("mineitemX1", SelfTestForm.CONTAINS, "mineitem_1", false),
            new SelfTest("plain ascii", SelfTestForm.NON_ASCII, null, false),
            new SelfTest("caf\u00e9", SelfTestForm.NON_ASCII, null, true),
            new SelfTest("\ud801\udc00", SelfTestForm.NON_ASCII, null, true));
}

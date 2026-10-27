package net.siftvanilla.siftcore.feature.friends;

/**
 * Cleans a friend note the same way team chat cleans messages: control and formatting characters and the legacy
 * section sign are removed, the rest is trimmed and cut to {@link #MAX_LENGTH} code points. Notes are always shown
 * as literal text, never parsed. Pure.
 */
public final class NoteText {

    /** The longest note in code points (the column holds 64 characters). */
    public static final int MAX_LENGTH = 64;

    /** What {@code /friend note <player> -} uses to clear a note. */
    public static final String CLEAR = "-";

    private NoteText() {
    }

    /** The cleaned note, empty when nothing is left. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_LENGTH * 2));
        text.codePoints().forEach(cp -> {
            if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT && cp != '§'
                && Character.isDefined(cp) && Character.getType(cp) != Character.SURROGATE) {
                sb.appendCodePoint(cp);
            }
        });
        return cut(sb.toString().strip(), MAX_LENGTH).strip();
    }

    /**
     * The text cut to at most {@code max} code points, never between the two halves of a surrogate pair (columns such
     * as {@code VARCHAR(32)} count characters, not UTF-16 units).
     */
    public static String cut(String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, Math.max(0, max)));
    }
}

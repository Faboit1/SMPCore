package net.siftvanilla.siftcore.feature.staff;

/** Normalises free text typed by players and staff (reasons, reports) before it is stored or shown. */
final class CleanText {

    private CleanText() {
    }

    /**
     * Removes control and formatting characters and the legacy colour sign, turns any run of whitespace into one
     * space and trims the ends. The result is still untrusted and must be inserted literally wherever it is shown.
     */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = !sb.isEmpty();
                continue;
            }
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '§') {
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}

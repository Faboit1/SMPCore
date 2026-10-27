package net.siftvanilla.siftcore.feature.integrations;

import java.io.IOException;
import java.io.Writer;

/**
 * RFC 4180 CSV lines: fields with a comma, quote or line break are quoted and quotes doubled. A field starting with
 * {@code = + - @} (and not a plain number) gets a leading apostrophe, so a player name or note can't run as a formula
 * when the file is opened in a spreadsheet.
 */
final class Csv {

    private Csv() {
    }

    /** Writes one line (fields joined by commas, CRLF at the end). Null fields are empty. */
    static void line(Writer out, Object... fields) throws IOException {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(field(fields[i]));
        }
        out.write("\r\n");
    }

    static String field(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 && !isNumber(text)) {
            text = "'" + text;
        }
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        return quote ? '"' + text.replace("\"", "\"\"") + '"' : text;
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

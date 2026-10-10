package net.siftvanilla.siftcore.feature.staff;

import java.util.Locale;
import java.util.Set;

/** Reads the command label out of a typed command line, the way the server resolves it. */
final class CommandLabels {

    private CommandLabels() {
    }

    /**
     * The lowercase label of {@code /minecraft:tell Bob hi} is {@code tell}: the leading slash, any namespace and
     * the arguments are dropped. Returns an empty string for an empty line.
     */
    static String label(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String text = commandLine.strip();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        int end = 0;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        String label = text.substring(0, end).toLowerCase(Locale.ROOT);
        int colon = label.lastIndexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    /** True when the command's label is one of {@code labels} (lowercase, without slashes). */
    static boolean matches(String commandLine, Set<String> labels) {
        String label = label(commandLine);
        return !label.isEmpty() && labels.contains(label);
    }
}

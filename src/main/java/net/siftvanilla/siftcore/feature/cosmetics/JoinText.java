package net.siftvanilla.siftcore.feature.cosmetics;

import java.util.function.Predicate;
import net.kyori.adventure.text.Component;

/**
 * Custom join and leave messages (Tycoon): short, written by the player, read by everyone. {@code {name}} marks
 * where the player's name goes; a message without it gets the name in front ({@code rolls in} reads
 * {@code Alex rolls in}), so a line always says who joined. Player text is only ever inserted as plain text. Pure
 * apart from the checks passed in, so it is unit tested.
 */
final class JoinText {

    /** The placeholder for the player's name. */
    static final String TOKEN = "{name}";

    /** Why a message is refused, or {@link #OK}. */
    enum Problem {
        OK,
        EMPTY,
        TOO_LONG,
        CHARACTERS,
        TOKENS,
        LINK,
        FILTERED
    }

    private JoinText() {
    }

    /**
     * Removes what a client should never send (control and invisible formatting characters, the legacy colour sign)
     * and squeezes runs of spaces.
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT || c == '§') {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!space) {
                    sb.append(' ');
                }
                space = true;
                continue;
            }
            space = false;
            sb.append(c);
        }
        return sb.toString().strip();
    }

    /**
     * Checks a cleaned message.
     *
     * @param maxLength the longest message (the {@code {name}} token counts as written)
     * @param link      whether the text has a web or server address
     * @param filtered  whether the chat word filter catches it
     */
    static Problem check(String message, int maxLength, Predicate<String> link, Predicate<String> filtered) {
        if (message == null || message.isBlank()) {
            return Problem.EMPTY;
        }
        if (message.length() > maxLength) {
            return Problem.TOO_LONG;
        }
        int tokens = count(message);
        if (tokens > 1) {
            return Problem.TOKENS;
        }
        String text = message.replace(TOKEN, "").strip();
        if (text.isEmpty()) {
            return Problem.EMPTY;
        }
        if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0 || text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            return Problem.CHARACTERS;
        }
        if (link.test(message.replace(TOKEN, " "))) {
            return Problem.LINK;
        }
        if (filtered.test(message.replace(TOKEN, " "))) {
            return Problem.FILTERED;
        }
        return Problem.OK;
    }

    /** How many {@code {name}} tokens the message has. */
    static int count(String message) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = message.indexOf(TOKEN, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + TOKEN.length();
        }
    }

    /** The message as written, with {@code {name}} in front when it has none. */
    static String withName(String message) {
        return message.contains(TOKEN) ? message : TOKEN + " " + message;
    }

    /** The line: the player's text as plain text with {@code name} where the token is. */
    static Component render(String message, Component name) {
        String full = withName(message);
        int at = full.indexOf(TOKEN);
        String before = full.substring(0, at);
        String after = full.substring(at + TOKEN.length());
        var builder = Component.text();
        if (!before.isEmpty()) {
            builder.append(Component.text(before));
        }
        builder.append(name);
        if (!after.isEmpty()) {
            builder.append(Component.text(after));
        }
        return builder.build();
    }
}

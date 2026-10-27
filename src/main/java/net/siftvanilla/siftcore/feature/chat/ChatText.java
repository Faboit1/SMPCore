package net.siftvanilla.siftcore.feature.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Small text helpers shared by public chat and private messages. Pure, thread-safe. */
final class ChatText {

    private ChatText() {
    }

    /**
     * Strips what a client should never send but a command or plugin could carry: control and invisible formatting
     * characters and the legacy section sign (which clients render as colours). Surrounding whitespace is trimmed and
     * runs of spaces are kept as typed.
     */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != '§') {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /** The plain text of a component (translations stay as their keys). */
    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}

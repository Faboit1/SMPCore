package net.siftvanilla.siftcore.core.link;

/**
 * The chat feature's checks for text that other players will read, for features that let players choose public
 * text of their own (nicknames, join messages). Implemented by the chat feature; consumed by the cosmetics feature.
 * Pure reads of the current chat config, safe from any thread.
 */
public interface TextChecks {

    /** Nothing is filtered and nothing is an address. */
    TextChecks NONE = new TextChecks() {
        @Override
        public boolean filtered(String text) {
            return false;
        }

        @Override
        public boolean link(String text) {
            return false;
        }
    };

    /** True when the text has a word of the chat word filter (whatever its action in chat). */
    boolean filtered(String text);

    /**
     * True when the text has a web or server address. Unlike chat, the server's own allowed addresses count too,
     * and addresses are found even when chat's link check is turned off.
     */
    boolean link(String text);
}

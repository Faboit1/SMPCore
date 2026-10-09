package net.siftvanilla.siftcore.core.text;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A lang file entry. Declares the placeholders the string may use (the lang validator rejects anything else), the
 * channel it is sent on and its sound. Declare keys as {@code static final} fields in a {@code *Messages} class
 * and register that class with {@link Lang#register(Class)}.
 *
 * @param status true for a repeating status line on the action bar (a countdown, a timer): it always stays on the
 *               action bar, whatever the player's feedback channel ({@link #status(String, String...)})
 */
public record MessageKey(String path, Set<String> placeholders, Channel channel, Feedback feedback, boolean status) {

    private static final Pattern PATH = Pattern.compile("[a-z0-9_-]+(\\.[a-z0-9_-]+)+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    public MessageKey {
        Objects.requireNonNull(path);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(feedback);
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid message path " + path);
        }
        for (String placeholder : placeholders) {
            if (!NAME.matcher(placeholder).matches()) {
                throw new IllegalArgumentException("Invalid placeholder name " + placeholder + " in " + path);
            }
        }
        placeholders = Set.copyOf(placeholders);
    }

    /** A key that is not a status line. */
    public MessageKey(String path, Set<String> placeholders, Channel channel, Feedback feedback) {
        this(path, placeholders, channel, feedback, false);
    }

    private static Set<String> set(String[] names) {
        return Set.copyOf(Arrays.asList(names));
    }

    /** A chat message worth keeping (receipts, incoming payments, broadcasts). */
    public static MessageKey chat(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.CHAT, Feedback.NONE);
    }

    /** A chat message with a notify sound (incoming requests, mentions). */
    public static MessageKey notify(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.CHAT, Feedback.NOTIFY);
    }

    /** Transient success feedback on the action bar (or in chat, if the player chose so). */
    public static MessageKey success(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.ACTIONBAR, Feedback.SUCCESS);
    }

    /** Transient error feedback on the action bar (or in chat, if the player chose so), in the error colours. */
    public static MessageKey error(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.ACTIONBAR, Feedback.ERROR);
    }

    /** Transient neutral information on the action bar. */
    public static MessageKey info(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.ACTIONBAR, Feedback.NONE);
    }

    /**
     * A repeating status line on the action bar (a combat timer, a countdown): sent again and again, so it never
     * moves to chat.
     */
    public static MessageKey status(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.ACTIONBAR, Feedback.NONE, true);
    }

    /** A rare title. */
    public static MessageKey title(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.TITLE, Feedback.NOTIFY);
    }

    /** Text used inside UI (GUI names, lore, dialog bodies, scoreboard lines); never sent on its own. */
    public static MessageKey ui(String path, String... placeholders) {
        return new MessageKey(path, set(placeholders), Channel.NONE, Feedback.NONE);
    }

    public MessageKey withFeedback(Feedback feedback) {
        return new MessageKey(this.path, this.placeholders, this.channel, feedback, this.status);
    }

    /**
     * This key as a repeating line: it stays on its channel whatever the player's feedback channel, and keeps its
     * sound and (for an error) the error colours. For refusals an event repeats many times a second while the player
     * keeps trying (walking into a border): on the action bar each line replaces the last, in chat they would pile up.
     */
    public MessageKey asStatus() {
        return new MessageKey(this.path, this.placeholders, this.channel, this.feedback, true);
    }
}

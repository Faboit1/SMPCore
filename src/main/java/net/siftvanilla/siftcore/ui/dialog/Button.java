package net.siftvanilla.siftcore.ui.dialog;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * A dialog button. The handler runs on the clicking player's thread after the submission was validated. A null
 * handler just closes the dialog.
 *
 * @param after what the client shows right after the click, before the server answers
 */
public record Button(Component label, Component tooltip, int width, Handler handler, After after) {

    public static final int DEFAULT_WIDTH = 200;

    /**
     * What the client does with the dialog when the button is clicked. Minecraft decides this per dialog, so a dialog
     * waits for the server when any of its buttons {@link #WAIT}s, closes at once when every button closes, and
     * otherwise stays on screen until the server shows the next one.
     */
    public enum After {
        /** The dialog stays on screen until the next dialog replaces it, or the server closes it (the default). */
        NEXT,
        /** The dialog closes on the client right away; the handler still runs (and may open something else). */
        CLOSE,
        /** The client shows its "waiting for response" screen until the server answers (slow work such as searches). */
        WAIT
    }

    public Button {
        Objects.requireNonNull(label);
        width = Math.clamp(width, 1, 1024);
        after = after == null ? After.NEXT : after;
    }

    public Button(Component label, Component tooltip, int width, Handler handler) {
        this(label, tooltip, width, handler, After.NEXT);
    }

    public static Button of(Component label, Handler handler) {
        return new Button(label, null, DEFAULT_WIDTH, handler);
    }

    public static Button of(Component label, Component tooltip, Handler handler) {
        return new Button(label, tooltip, DEFAULT_WIDTH, handler);
    }

    public Button width(int width) {
        return new Button(this.label, this.tooltip, width, this.handler, this.after);
    }

    /** Closes the dialog as soon as it is clicked (for buttons that finish something, like Teleport or Done). */
    public Button closes() {
        return new Button(this.label, this.tooltip, this.width, this.handler, After.CLOSE);
    }

    /** Shows the client's waiting screen until the server answers (for slow work such as searching). */
    public Button waits() {
        return new Button(this.label, this.tooltip, this.width, this.handler, After.WAIT);
    }

    /** Whether the client may close the dialog on its own when this button is clicked. */
    boolean closesOnClient() {
        return this.handler == null || this.after == After.CLOSE;
    }

    /** What a button does when clicked. */
    @FunctionalInterface
    public interface Handler {
        void handle(Submission submission);
    }
}

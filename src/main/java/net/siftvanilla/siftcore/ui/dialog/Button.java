package net.siftvanilla.siftcore.ui.dialog;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * A plain, unstyled dialog button. The handler runs on the clicking player's thread after the submission was
 * validated. A null handler just closes the dialog.
 */
public record Button(Component label, Component tooltip, int width, Handler handler) {

    public static final int DEFAULT_WIDTH = 200;

    public Button {
        Objects.requireNonNull(label);
        width = Math.clamp(width, 1, 1024);
    }

    public static Button of(Component label, Handler handler) {
        return new Button(label, null, DEFAULT_WIDTH, handler);
    }

    public static Button of(Component label, Component tooltip, Handler handler) {
        return new Button(label, tooltip, DEFAULT_WIDTH, handler);
    }

    public Button width(int width) {
        return new Button(this.label, this.tooltip, width, this.handler);
    }

    /** What a button does when clicked. */
    @FunctionalInterface
    public interface Handler {
        void handle(Submission submission);
    }
}

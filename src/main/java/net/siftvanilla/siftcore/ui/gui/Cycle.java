package net.siftvanilla.siftcore.ui.gui;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * A cycling option button (sort orders, filters). Left click moves forward, right click backward. Rendered as the
 * button name followed by every option in gray with a bullet, the selected one in white.
 */
public final class Cycle<T> {

    /** One option with its label. */
    public record Option<T>(String id, Component label, T value) {
    }

    private final List<Option<T>> options;
    private int index;

    public Cycle(List<Option<T>> options, String initialId) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("A cycle needs options");
        }
        this.options = List.copyOf(options);
        this.index = 0;
        for (int i = 0; i < options.size(); i++) {
            if (Objects.equals(options.get(i).id(), initialId)) {
                this.index = i;
            }
        }
    }

    public List<Option<T>> options() {
        return this.options;
    }

    public Option<T> selected() {
        return this.options.get(this.index);
    }

    public T value() {
        return selected().value();
    }

    public int index() {
        return this.index;
    }

    public void next() {
        this.index = (this.index + 1) % this.options.size();
    }

    public void previous() {
        this.index = (this.index - 1 + this.options.size()) % this.options.size();
    }

    /** Moves according to the click: right click goes back, anything else forward. */
    public void step(ClickContext click) {
        if (click.right()) {
            previous();
        } else {
            next();
        }
    }
}

package net.siftvanilla.siftcore.ui.dialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * One dialog screen, independent of how it is rendered (Java dialog or Bedrock form). Build it with
 * {@link Templates} so every dialog has the same structure.
 *
 * @param kind      the template
 * @param title     plain text title, no styling
 * @param body      text and items shown above the inputs
 * @param inputs    form inputs (any template may carry inputs, forms usually do)
 * @param buttons   action buttons in order; a notice has one, a confirmation two (yes, no)
 * @param exit      for lists: the footer button (back/close), or null
 * @param columns   for lists: the number of button columns
 * @param escapable whether Escape closes the dialog
 */
public record View(Kind kind, Component title, List<Body> body, List<Input> inputs, List<Button> buttons, Button exit,
                   int columns, boolean escapable) {

    /** The shared templates. */
    public enum Kind {
        NOTICE,
        CONFIRM,
        LIST,
        FORM
    }

    public View {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(title);
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
        columns = Math.max(1, columns);
        switch (kind) {
            case NOTICE -> {
                if (buttons.size() != 1) {
                    throw new IllegalArgumentException("A notice has exactly one button");
                }
            }
            case CONFIRM -> {
                if (buttons.size() != 2) {
                    throw new IllegalArgumentException("A confirmation has exactly two buttons");
                }
            }
            case LIST, FORM -> {
                if (buttons.isEmpty() && exit == null) {
                    throw new IllegalArgumentException("A list or form needs at least one button");
                }
            }
        }
        long distinct = inputs.stream().map(Input::key).distinct().count();
        if (distinct != inputs.size()) {
            throw new IllegalArgumentException("Input keys must be unique");
        }
    }

    /** All clickable buttons including the exit button, indexed as the router expects. */
    public List<Button> allButtons() {
        if (this.exit == null) {
            return this.buttons;
        }
        List<Button> all = new ArrayList<>(this.buttons);
        all.add(this.exit);
        return all;
    }

    /**
     * A copy whose buttons show the client's "waiting for response" screen after a click, for dialogs whose answer
     * takes a moment, such as searches. See {@link Button.After}.
     */
    public View waiting() {
        List<Button> waiting = new ArrayList<>(this.buttons.size());
        for (Button button : this.buttons) {
            waiting.add(button.waits());
        }
        return new View(this.kind, this.title, this.body, this.inputs, waiting, this.exit == null ? null : this.exit.waits(),
            this.columns, this.escapable);
    }

    /** A copy with an error line appended to the body and inputs pre-filled with what the player typed. */
    public View withError(Component error, FormValues typed) {
        List<Body> newBody = new ArrayList<>();
        for (Body element : this.body) {
            if (!(element instanceof Body.Text text && text.error())) {
                newBody.add(element);
            }
        }
        newBody.add(Body.error(error));
        List<Input> newInputs = new ArrayList<>();
        for (Input input : this.inputs) {
            newInputs.add(prefill(input, typed));
        }
        return new View(this.kind, this.title, newBody, newInputs, this.buttons, this.exit, this.columns, this.escapable);
    }

    private static Input prefill(Input input, FormValues typed) {
        if (typed == null || typed.raw(input.key()).isEmpty()) {
            return input;
        }
        return switch (input) {
            case Input.Text t -> t.withInitial(typed.text(t.key()));
            case Input.Toggle t -> t.withInitial(typed.toggle(t.key()));
            case Input.Choice c -> c.allows(typed.choice(c.key())) ? c.withInitial(typed.choice(c.key())) : c;
            case Input.Range r -> r.allows(typed.number(r.key())) ? r.withInitial(typed.number(r.key())) : r;
        };
    }
}

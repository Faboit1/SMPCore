package net.siftvanilla.siftcore.integration.floodgate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.View;

/**
 * How one dialog {@link View} becomes a Bedrock form, and how a form answer becomes the dialog click the router
 * expects (an index into {@link View#allButtons()} plus the raw input values). Pure: it knows nothing about Cumulus,
 * so the mapping is unit-tested and the Floodgate bridge only renders the plan.
 * <p>
 * The rules, so Bedrock players get the same flows as Java players:
 * <ul>
 *   <li>A notice becomes a modal form: its button, plus a close button that does nothing (like pressing Escape).</li>
 *   <li>A confirmation becomes a modal form with its two buttons in order.</li>
 *   <li>A list becomes a simple form: the list buttons in order, then its footer (back or close).</li>
 *   <li>A view with inputs (every form) becomes a custom form: the body as a label, then one component per input
 *       (text to input, toggle to toggle, choice to dropdown, range to slider). Submitting presses the first button.
 *       A form's second button is its cancel or back button (the {@link net.siftvanilla.siftcore.ui.dialog.Templates}
 *       layout), so closing the form presses it: back goes back, cancel closes. When a view with inputs has more
 *       buttons than that, a final dropdown picks which one submitting presses.</li>
 * </ul>
 * Whatever the client answers is still validated by the dialog router against the view's own inputs.
 */
public final class FormPlan {

    /** The Cumulus form type. */
    public enum Type {
        MODAL,
        SIMPLE,
        CUSTOM
    }

    /** No view button: the answer only closes the form. */
    public static final int CLOSE = -1;

    /** The key of the action dropdown added to custom forms with more than two buttons. */
    public static final String ACTION_KEY = "__action";

    /** A custom form component, in display order. */
    public sealed interface Field {

        String label();

        /** Text only (the body). Answers carry no value for it. */
        record Label(String label) implements Field {
        }

        record Text(String key, String label, String initial) implements Field {
        }

        record Toggle(String key, String label, boolean initial) implements Field {
        }

        /** A dropdown; answers are the index of the picked option. */
        record Dropdown(String key, String label, List<String> options, List<String> ids, int initial) implements Field {
            public Dropdown {
                options = List.copyOf(options);
                ids = List.copyOf(ids);
            }
        }

        record Slider(String key, String label, float min, float max, float step, float initial) implements Field {
        }
    }

    /** A dialog click: the index into {@link View#allButtons()} and the raw input values. */
    public record Answer(int button, Map<String, Object> values) {
        public Answer {
            values = Map.copyOf(values);
        }
    }

    /** Turns text components into the plain or section-coloured text Bedrock forms show. */
    public interface Renderer {
        String text(Component component);

        /** One line describing an item shown in a dialog body. */
        String item(Body.Item item);

        /** The label of the extra close button a notice gets (modal forms always have two buttons). */
        String closeLabel();

        /** The label of the dropdown that picks the button of a form with more than two buttons. */
        String actionLabel();
    }

    private final Type type;
    private final String title;
    private final String content;
    private final List<String> buttons;
    private final int[] targets;
    private final List<Field> fields;
    private final int submit;
    private final int closed;
    private final List<Integer> actionTargets;

    private FormPlan(Type type, String title, String content, List<String> buttons, int[] targets, List<Field> fields,
                     int submit, int closed, List<Integer> actionTargets) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.buttons = List.copyOf(buttons);
        this.targets = targets.clone();
        this.fields = List.copyOf(fields);
        this.submit = submit;
        this.closed = closed;
        this.actionTargets = List.copyOf(actionTargets);
    }

    /** Plans the form for a view. */
    public static FormPlan of(View view, Renderer renderer) {
        String title = renderer.text(view.title());
        String content = content(view, renderer);
        List<Button> all = view.allButtons();
        if (!view.inputs().isEmpty() || view.kind() == View.Kind.FORM) {
            return custom(view, renderer, title, content, all);
        }
        return switch (view.kind()) {
            case NOTICE -> new FormPlan(Type.MODAL, title, content,
                List.of(renderer.text(all.getFirst().label()), renderer.closeLabel()), new int[] {0, CLOSE}, List.of(), CLOSE, CLOSE, List.of());
            case CONFIRM -> new FormPlan(Type.MODAL, title, content,
                List.of(renderer.text(all.get(0).label()), renderer.text(all.get(1).label())), new int[] {0, 1}, List.of(),
                CLOSE, CLOSE, List.of());
            case LIST, FORM -> {
                List<String> labels = new ArrayList<>(all.size());
                int[] targets = new int[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    labels.add(renderer.text(all.get(i).label()));
                    targets[i] = i;
                }
                yield new FormPlan(Type.SIMPLE, title, content, labels, targets, List.of(), CLOSE, CLOSE, List.of());
            }
        };
    }

    private static FormPlan custom(View view, Renderer renderer, String title, String content, List<Button> all) {
        List<Field> fields = new ArrayList<>();
        if (!content.isEmpty()) {
            fields.add(new Field.Label(content));
        }
        for (Input input : view.inputs()) {
            String label = renderer.text(input.label());
            fields.add(switch (input) {
                case Input.Text t -> new Field.Text(t.key(), label, t.initial());
                case Input.Toggle t -> new Field.Toggle(t.key(), label, t.initial());
                case Input.Choice c -> {
                    List<String> options = new ArrayList<>(c.options().size());
                    List<String> ids = new ArrayList<>(c.options().size());
                    int initial = 0;
                    for (int i = 0; i < c.options().size(); i++) {
                        Input.Option option = c.options().get(i);
                        options.add(renderer.text(option.label()));
                        ids.add(option.id());
                        if (option.id().equals(c.initial())) {
                            initial = i;
                        }
                    }
                    yield new Field.Dropdown(c.key(), label, options, ids, initial);
                }
                case Input.Range r -> new Field.Slider(r.key(), label, r.min(), r.max(), r.step(), r.initial());
            });
        }
        if (all.size() == 1) {
            return new FormPlan(Type.CUSTOM, title, content, List.of(), new int[0], fields, 0, CLOSE, List.of());
        }
        if (all.size() == 2 && view.kind() == View.Kind.FORM) {
            return new FormPlan(Type.CUSTOM, title, content, List.of(), new int[0], fields, 0, 1, List.of());
        }
        List<String> labels = new ArrayList<>(all.size());
        List<Integer> targets = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            labels.add(renderer.text(all.get(i).label()));
            targets.add(i);
        }
        fields.add(new Field.Dropdown(ACTION_KEY, renderer.actionLabel(), labels, labels, 0));
        return new FormPlan(Type.CUSTOM, title, content, List.of(), new int[0], fields, CLOSE, CLOSE, targets);
    }

    private static String content(View view, Renderer renderer) {
        StringBuilder sb = new StringBuilder();
        for (Body body : view.body()) {
            String line = switch (body) {
                case Body.Text text -> renderer.text(text.text());
                case Body.Item item -> renderer.item(item);
            };
            if (line.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    public Type type() {
        return this.type;
    }

    public String title() {
        return this.title;
    }

    /** The body text (modal and simple forms; custom forms carry it as their first label). */
    public String content() {
        return this.content;
    }

    /** Button labels of a modal (always two) or simple form. */
    public List<String> buttons() {
        return this.buttons;
    }

    /** Components of a custom form. */
    public List<Field> fields() {
        return this.fields;
    }

    /** The answer for a clicked modal or simple form button, empty when the button only closes. */
    public Optional<Answer> clicked(int formButton) {
        if (this.type == Type.CUSTOM || formButton < 0 || formButton >= this.targets.length || this.targets[formButton] == CLOSE) {
            return Optional.empty();
        }
        return Optional.of(new Answer(this.targets[formButton], Map.of()));
    }

    /**
     * The answer for a submitted custom form. {@code responses} holds one value per field in order (null for labels):
     * String for text, Boolean for toggles, Integer for dropdowns, Float for sliders. Values of the wrong type are
     * left out, so the router reports that input as invalid.
     */
    public Optional<Answer> submitted(List<?> responses) {
        if (this.type != Type.CUSTOM) {
            return Optional.empty();
        }
        Map<String, Object> values = new HashMap<>();
        int button = this.submit;
        for (int i = 0; i < this.fields.size(); i++) {
            Object value = i < responses.size() ? responses.get(i) : null;
            switch (this.fields.get(i)) {
                case Field.Label ignored -> {
                }
                case Field.Text t -> values.put(t.key(), value instanceof String s ? s : "");
                case Field.Toggle t -> {
                    if (value instanceof Boolean b) {
                        values.put(t.key(), b);
                    }
                }
                case Field.Slider s -> {
                    if (value instanceof Number n) {
                        values.put(s.key(), n.floatValue());
                    }
                }
                case Field.Dropdown d -> {
                    int index = value instanceof Number n ? n.intValue() : -1;
                    if (d.key().equals(ACTION_KEY)) {
                        button = index >= 0 && index < this.actionTargets.size() ? this.actionTargets.get(index) : CLOSE;
                    } else if (index >= 0 && index < d.ids().size()) {
                        values.put(d.key(), d.ids().get(index));
                    }
                }
            }
        }
        return button == CLOSE ? Optional.empty() : Optional.of(new Answer(button, values));
    }

    /**
     * The answer when the player closed the form, empty when closing does nothing. Closing a form presses its cancel
     * or back button with the inputs as they were shown, so the router's validation passes and the button runs.
     */
    public Optional<Answer> closed() {
        if (this.closed == CLOSE) {
            return Optional.empty();
        }
        Map<String, Object> values = new HashMap<>();
        for (Field field : this.fields) {
            switch (field) {
                case Field.Label ignored -> {
                }
                case Field.Text t -> values.put(t.key(), t.initial());
                case Field.Toggle t -> values.put(t.key(), t.initial());
                case Field.Slider s -> values.put(s.key(), s.initial());
                case Field.Dropdown d -> {
                    if (!d.key().equals(ACTION_KEY)) {
                        values.put(d.key(), d.ids().get(d.initial()));
                    }
                }
            }
        }
        return Optional.of(new Answer(this.closed, values));
    }
}

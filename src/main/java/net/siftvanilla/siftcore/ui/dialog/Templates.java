package net.siftvanilla.siftcore.ui.dialog;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Lang;

/**
 * The four dialog templates every screen is built from, so all dialogs share one structure:
 * a plain title, a white/gray body, optional inputs, and plain buttons whose labels come from the lang file.
 * <ul>
 *   <li>{@link #notice}: information and one button;</li>
 *   <li>{@link #confirm}: a yes/no decision;</li>
 *   <li>{@link #list}: several actions plus a back/close footer;</li>
 *   <li>{@link #form}: inputs with submit and cancel.</li>
 * </ul>
 */
public final class Templates {

    /** Width used for wide single-column buttons. */
    public static final int WIDE = 250;

    private final Lang lang;

    public Templates(Lang lang) {
        this.lang = lang;
    }

    public Lang lang() {
        return this.lang;
    }

    private static List<Body> body(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return List.of(Body.text(Component.join(JoinConfiguration.newlines(), lines)));
    }

    /** A notice whose button runs {@code onOk} (null just closes). */
    public View notice(Component title, List<Component> lines, Button.Handler onOk) {
        return new View(View.Kind.NOTICE, title, body(lines), List.of(),
            List.of(Button.of(this.lang.get(CoreMessages.UI_OK), onOk).width(WIDE)), null, 1, true);
    }

    /** A notice with a custom button label. */
    public View notice(Component title, List<Component> lines, Component buttonLabel, Button.Handler onOk) {
        return new View(View.Kind.NOTICE, title, body(lines), List.of(),
            List.of(Button.of(buttonLabel, onOk).width(WIDE)), null, 1, true);
    }

    /** A yes/no confirmation; {@code onNo} null just closes. */
    public View confirm(Component title, List<Component> lines, Button.Handler onYes, Button.Handler onNo) {
        return confirm(title, lines, this.lang.get(CoreMessages.UI_CONFIRM), this.lang.get(CoreMessages.UI_CANCEL), onYes, onNo);
    }

    public View confirm(Component title, List<Component> lines, Component yesLabel, Component noLabel,
                        Button.Handler onYes, Button.Handler onNo) {
        return new View(View.Kind.CONFIRM, title, body(lines), List.of(),
            List.of(Button.of(yesLabel, onYes).width(150), Button.of(noLabel, onNo).width(150)), null, 2, true);
    }

    /** A confirmation that also carries body items (e.g. the item being bought). */
    public View confirmWithBody(Component title, List<Body> body, Component yesLabel, Component noLabel,
                                Button.Handler onYes, Button.Handler onNo) {
        return new View(View.Kind.CONFIRM, title, body, List.of(),
            List.of(Button.of(yesLabel, onYes).width(150), Button.of(noLabel, onNo).width(150)), null, 2, true);
    }

    /**
     * A list of actions in {@code columns} columns, with a footer button: "Back" running {@code back}, or "Close"
     * when {@code back} is null.
     */
    public View list(Component title, List<Component> lines, List<Button> buttons, int columns, Button.Handler back) {
        Button footer = back == null
            ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null).width(WIDE)
            : Button.of(this.lang.get(CoreMessages.UI_BACK), back).width(WIDE);
        return new View(View.Kind.LIST, title, body(lines), List.of(), buttons, footer, columns, true);
    }

    /** A list whose body carries items or custom elements. */
    public View listWithBody(Component title, List<Body> body, List<Button> buttons, int columns, Button.Handler back) {
        Button footer = back == null
            ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null).width(WIDE)
            : Button.of(this.lang.get(CoreMessages.UI_BACK), back).width(WIDE);
        return new View(View.Kind.LIST, title, body, List.of(), buttons, footer, columns, true);
    }

    /** A form: inputs, then Submit and Cancel (cancel null just closes). */
    public View form(Component title, List<Component> lines, List<Input> inputs, Button.Handler submit, Button.Handler cancel) {
        return form(title, lines, inputs, this.lang.get(CoreMessages.UI_SUBMIT), submit, cancel);
    }

    public View form(Component title, List<Component> lines, List<Input> inputs, Component submitLabel,
                     Button.Handler submit, Button.Handler cancel) {
        List<Button> buttons = new ArrayList<>(2);
        buttons.add(Button.of(submitLabel, submit).width(150));
        buttons.add(Button.of(this.lang.get(cancel == null ? CoreMessages.UI_CANCEL : CoreMessages.UI_BACK), cancel).width(150));
        return new View(View.Kind.FORM, title, body(lines), inputs, buttons, null, 2, true);
    }

    // ------------------------------------------------------------ input helpers

    public static Input.Text text(String key, Component label, String initial, int maxLength) {
        return new Input.Text(key, label, initial, maxLength, 1, 250);
    }

    public static Input.Toggle toggle(String key, Component label, boolean initial) {
        return new Input.Toggle(key, label, initial);
    }

    public static Input.Choice choice(String key, Component label, List<Input.Option> options, String initial) {
        return new Input.Choice(key, label, options, initial, 250);
    }

    public static Input.Range range(String key, Component label, long min, long max, long step, long initial) {
        return new Input.Range(key, label, min, max, step, initial, null, 250);
    }
}

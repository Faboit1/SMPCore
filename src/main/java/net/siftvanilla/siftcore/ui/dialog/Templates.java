package net.siftvanilla.siftcore.ui.dialog;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;

/**
 * The four dialog templates every screen is built from, so all dialogs share one structure:
 * a plain title, a short body (only what the player needs to decide), optional inputs, and buttons whose labels come
 * from the lang file and whose tooltips say what they do (the dialog style, {@code docs/development.md}).
 * <ul>
 *   <li>{@link #notice}: information and one button;</li>
 *   <li>{@link #confirm}: a yes/no decision;</li>
 *   <li>{@link #list}: several actions plus a back/close footer; {@link #column} and {@link #grid} lay out many
 *       buttons in one or two columns with nothing above them (no paging: the dialog scrolls);</li>
 *   <li>{@link #form}: inputs with submit and cancel; {@link #number} picks one number with a slider.</li>
 * </ul>
 * Buttons that show a state: {@link #switchButton} ("Sounds: ON", ON green and OFF red) and {@link #choiceButton}
 * ("Mention sound: Bell", the value coloured).
 */
public final class Templates {

    /** Width used for wide single-column buttons. */
    public static final int WIDE = 250;
    /** Width of the buttons of a {@link #column}: room for a label and its value ("Who can see my balance: Friends"). */
    public static final int LONG = 300;
    /** Width of the buttons of a {@link #grid} (two side by side). */
    public static final int HALF = 150;

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

    /**
     * Many buttons in one column of {@link #LONG} buttons with nothing above them, then Back running {@code back}
     * (Close when null). For lists of settings, toggles and choices: no paging, the dialog scrolls.
     */
    public View column(Component title, List<Button> buttons, Button.Handler back) {
        return column(title, List.of(), buttons, back);
    }

    /** {@link #column(Component, List, Button.Handler)} with a few short lines above (a status the player needs). */
    public View column(Component title, List<Component> lines, List<Button> buttons, Button.Handler back) {
        return laidOut(title, lines, buttons, 1, LONG, back);
    }

    /**
     * Many buttons two by two ({@link #HALF} wide) with nothing above them, then Back running {@code back} (Close when
     * null). For short labels, like the groups of a menu.
     */
    public View grid(Component title, List<Button> buttons, Button.Handler back) {
        return grid(title, List.of(), buttons, back);
    }

    /** {@link #grid(Component, List, Button.Handler)} with a few short lines above. */
    public View grid(Component title, List<Component> lines, List<Button> buttons, Button.Handler back) {
        return laidOut(title, lines, buttons, 2, HALF, back);
    }

    private View laidOut(Component title, List<Component> lines, List<Button> buttons, int columns, int width, Button.Handler back) {
        List<Button> sized = new ArrayList<>(buttons.size());
        for (Button button : buttons) {
            sized.add(button.width(width));
        }
        Button footer = back == null
            ? Button.of(this.lang.get(CoreMessages.UI_CLOSE), null).width(LONG)
            : Button.of(this.lang.get(CoreMessages.UI_BACK), back).width(LONG);
        return new View(View.Kind.LIST, title, body(lines), List.of(), sized, footer, columns, true);
    }

    /** "ON" in the on colour or "OFF" in the off colour. */
    public Component state(boolean on) {
        return this.lang.get(on ? CoreMessages.UI_ON : CoreMessages.UI_OFF);
    }

    /**
     * A switch: "Label: ON" with ON in the on colour, or "Label: OFF" with OFF in the off colour. The handler flips it
     * at once and shows the same page again with the new state; the tooltip says what it does.
     */
    public Button switchButton(Component label, boolean on, Component tooltip, Button.Handler handler) {
        return choiceButton(label, state(on), tooltip, handler);
    }

    /**
     * A button that shows a value, "Label: value": {@code value} keeps its own colour (a chosen option in the accent
     * colour, money green, shards purple), and an uncoloured value is written in the accent colour. For choices whose
     * click moves to the next option, and for values a click opens a small dialog to change.
     */
    public Button choiceButton(Component label, Component value, Component tooltip, Button.Handler handler) {
        Component shown = value.colorIfAbsent(this.lang.style().palette().accent());
        return new Button(this.lang.get(CoreMessages.UI_VALUE_BUTTON, Arg.component("label", label), Arg.component("value", shown)),
            tooltip, Button.DEFAULT_WIDTH, handler);
    }

    /** Lines joined into one text, for a button's tooltip (null when there are none). */
    public static Component lines(List<Component> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /**
     * A small dialog that picks one number with a slider: Done runs {@code done} (read the value with
     * {@code submission.values().number(range.key())}; the router already refused values off the slider's range and
     * step), Back runs {@code back} (Cancel, which just closes, when null).
     */
    public View number(Component title, Input.Range range, Button.Handler done, Button.Handler back) {
        return form(title, List.of(), List.of(range), this.lang.get(CoreMessages.UI_DONE), done, back);
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

package net.siftvanilla.siftcore.integration.floodgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Input;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.junit.jupiter.api.Test;

/** Dialog views become the right Bedrock forms, and form answers become the right dialog clicks. */
class FormPlanTest {

    private static final FormPlan.Renderer PLAIN = new FormPlan.Renderer() {
        @Override
        public String text(Component component) {
            return PlainTextComponentSerializer.plainText().serialize(component);
        }

        @Override
        public String item(Body.Item item) {
            return "item";
        }

        @Override
        public String closeLabel() {
            return "Close";
        }

        @Override
        public String actionLabel() {
            return "Action";
        }
    };

    private static Button button(String label) {
        return Button.of(Component.text(label), submission -> { });
    }

    private static Component text(String text) {
        return Component.text(text);
    }

    @Test
    void aNoticeIsAModalWhoseSecondButtonOnlyCloses() {
        View view = new View(View.Kind.NOTICE, text("Sold"), List.of(Body.text(text("You sold it."))), List.of(),
            List.of(button("Okay")), null, 1, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals(FormPlan.Type.MODAL, plan.type());
        assertEquals("Sold", plan.title());
        assertEquals("You sold it.", plan.content());
        assertEquals(List.of("Okay", "Close"), plan.buttons());
        assertEquals(0, plan.clicked(0).orElseThrow().button());
        assertTrue(plan.clicked(1).isEmpty(), "the close button presses nothing");
        assertTrue(plan.closed().isEmpty());
    }

    @Test
    void aConfirmationIsAModalWithYesThenNo() {
        View view = new View(View.Kind.CONFIRM, text("Pay $500?"), List.of(Body.text(text("To Alex"))), List.of(),
            List.of(button("Pay"), button("Cancel")), null, 2, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals(FormPlan.Type.MODAL, plan.type());
        assertEquals(List.of("Pay", "Cancel"), plan.buttons());
        assertEquals(0, plan.clicked(0).orElseThrow().button());
        assertEquals(1, plan.clicked(1).orElseThrow().button());
        assertTrue(plan.clicked(2).isEmpty(), "a forged button index is ignored");
        assertTrue(plan.clicked(-1).isEmpty());
    }

    @Test
    void aListIsASimpleFormWithItsFooterLast() {
        View view = new View(View.Kind.LIST, text("Menu"), List.of(Body.text(text("Line one")), Body.text(text("Line two"))),
            List.of(), List.of(button("Shop"), button("Sell"), button("Homes")), button("Close"), 2, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals(FormPlan.Type.SIMPLE, plan.type());
        assertEquals("Line one\nLine two", plan.content());
        assertEquals(List.of("Shop", "Sell", "Homes", "Close"), plan.buttons());
        for (int i = 0; i < 4; i++) {
            assertEquals(i, plan.clicked(i).orElseThrow().button());
        }
        assertTrue(plan.clicked(4).isEmpty());
        assertTrue(plan.closed().isEmpty(), "closing a list does nothing, like Escape");
    }

    private static View payForm() {
        List<Input> inputs = List.of(
            new Input.Text("player", text("Player"), "Alex", 16, 1, 250),
            new Input.Toggle("anonymous", text("Hide my name"), true),
            new Input.Choice("currency", text("Currency"), List.of(new Input.Option("money", text("Money")),
                new Input.Option("shards", text("Shards"))), "shards", 250),
            new Input.Range("amount", text("Amount"), 1, 64, 1, 16, null, 250));
        return new View(View.Kind.FORM, text("Pay"), List.of(Body.text(text("Who and how much"))), inputs,
            List.of(button("Pay"), button("Back")), null, 2, true);
    }

    @Test
    void aFormIsACustomFormWithOneComponentPerInput() {
        FormPlan plan = FormPlan.of(payForm(), PLAIN);
        assertEquals(FormPlan.Type.CUSTOM, plan.type());
        List<FormPlan.Field> fields = plan.fields();
        assertEquals(5, fields.size());
        assertEquals("Who and how much", assertInstanceOf(FormPlan.Field.Label.class, fields.get(0)).label());
        FormPlan.Field.Text player = assertInstanceOf(FormPlan.Field.Text.class, fields.get(1));
        assertEquals("Alex", player.initial());
        assertTrue(assertInstanceOf(FormPlan.Field.Toggle.class, fields.get(2)).initial());
        FormPlan.Field.Dropdown currency = assertInstanceOf(FormPlan.Field.Dropdown.class, fields.get(3));
        assertEquals(List.of("Money", "Shards"), currency.options());
        assertEquals(1, currency.initial(), "the initial option is preselected");
        FormPlan.Field.Slider amount = assertInstanceOf(FormPlan.Field.Slider.class, fields.get(4));
        assertEquals(1f, amount.min());
        assertEquals(64f, amount.max());
        assertEquals(1f, amount.step());
        assertEquals(16f, amount.initial());
    }

    @Test
    void submittingAFormPressesItsFirstButtonWithTheValuesTheRouterExpects() {
        FormPlan plan = FormPlan.of(payForm(), PLAIN);
        FormPlan.Answer answer = plan.submitted(Arrays.asList(null, "Steve", false, 0, 32.0f)).orElseThrow();
        assertEquals(0, answer.button());
        assertEquals(Map.of("player", "Steve", "anonymous", false, "currency", "money", "amount", 32.0f), answer.values());
    }

    @Test
    void wrongValueTypesAreLeftOutSoTheRouterRejectsThem() {
        FormPlan plan = FormPlan.of(payForm(), PLAIN);
        FormPlan.Answer answer = plan.submitted(Arrays.asList(null, null, "yes", 7, "lots")).orElseThrow();
        assertEquals(Map.of("player", ""), answer.values(), "a missing text is empty; a bad toggle, option and slider are left out");
    }

    @Test
    void closingAFormPressesBackWithTheShownValues() {
        FormPlan plan = FormPlan.of(payForm(), PLAIN);
        FormPlan.Answer answer = plan.closed().orElseThrow();
        assertEquals(1, answer.button());
        assertEquals(Map.of("player", "Alex", "anonymous", true, "currency", "shards", "amount", 16.0f), answer.values());
    }

    @Test
    void aFormWithOneButtonIgnoresClosing() {
        View view = new View(View.Kind.FORM, text("Rename"), List.of(), List.of(new Input.Text("name", text("Name"), "", 16, 1, 250)),
            List.of(button("Save")), null, 1, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals(1, plan.fields().size(), "no label when the body is empty");
        assertTrue(plan.closed().isEmpty());
        assertEquals(0, plan.submitted(List.of("Base")).orElseThrow().button());
    }

    @Test
    void moreButtonsThanSubmitAndBackAddAnActionDropdown() {
        View view = new View(View.Kind.FORM, text("Listing"), List.of(), List.of(new Input.Text("price", text("Price"), "", 16, 1, 250)),
            List.of(button("Save"), button("Remove"), button("Back")), null, 3, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        FormPlan.Field.Dropdown action = assertInstanceOf(FormPlan.Field.Dropdown.class, plan.fields().getLast());
        assertEquals(FormPlan.ACTION_KEY, action.key());
        assertEquals("Action", action.label());
        assertEquals(List.of("Save", "Remove", "Back"), action.options());
        FormPlan.Answer remove = plan.submitted(Arrays.asList("10", 1)).orElseThrow();
        assertEquals(1, remove.button());
        assertEquals(Map.of("price", "10"), remove.values(), "the action dropdown is not an input value");
        assertTrue(plan.submitted(Arrays.asList("10", 9)).isEmpty(), "a forged action index presses nothing");
        assertTrue(plan.closed().isEmpty(), "closing never guesses which of several buttons to press");
    }

    @Test
    void aListWithInputsBecomesACustomFormWithAnActionDropdown() {
        View view = new View(View.Kind.LIST, text("Settings"), List.of(), List.of(new Input.Toggle("sound", text("Sounds"), true)),
            List.of(button("Save")), button("Back"), 1, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals(FormPlan.Type.CUSTOM, plan.type());
        FormPlan.Answer back = plan.submitted(Arrays.asList(false, 1)).orElseThrow();
        assertEquals(1, back.button());
        assertEquals(Map.of("sound", false), back.values());
    }

    @Test
    void itemsInTheBodyAreDescribedInTheContent() {
        View view = new View(View.Kind.LIST, text("Buy"), List.of(new Body.Item(null, null, true), Body.text(text("For $10"))),
            List.of(), List.of(button("Buy")), button("Back"), 1, true);
        FormPlan plan = FormPlan.of(view, PLAIN);
        assertEquals("item\nFor $10", plan.content());
    }
}

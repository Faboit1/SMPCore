package net.siftvanilla.siftcore.integration.floodgate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.FormBridge;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Shows SiftCore's dialogs to Bedrock players (joined through Geyser and Floodgate) as Cumulus forms, so every flow
 * a Java player has in a dialog works the same on Bedrock. {@link FormPlan} decides the form; this class only builds
 * it and turns the form answer back into the dialog click the router expects. The router then validates the answer
 * and runs the button's handler on the player's thread, exactly as for a Java dialog click.
 * <p>
 * Only loaded after checking that Floodgate (plugin name {@code floodgate}) is enabled. Floodgate 2.2.5 bundles
 * Cumulus 1.1.2 unrelocated, which is what this is compiled against. Answers arrive on Floodgate's network threads;
 * the router hops to the player's thread itself.
 */
public final class FloodgateForms implements FormBridge {

    /** The plugin name Floodgate registers under. */
    public static final String PLUGIN = "floodgate";

    private final FloodgateApi api;
    private final Supplier<Component> closeLabel;
    private final Supplier<Component> actionLabel;
    private final Logger logger;

    private FloodgateForms(FloodgateApi api, Supplier<Component> closeLabel, Supplier<Component> actionLabel, Logger logger) {
        this.api = api;
        this.closeLabel = closeLabel;
        this.actionLabel = actionLabel;
        this.logger = logger;
    }

    /**
     * A bridge over the running Floodgate, to install with {@code Dialogs#bedrock(FormBridge)}.
     *
     * @param closeLabel  the label of the extra button a notice gets
     * @param actionLabel the label of the dropdown that picks the button of a form with more than two buttons
     */
    public static FormBridge create(Supplier<Component> closeLabel, Supplier<Component> actionLabel, Logger logger) {
        return new FloodgateForms(FloodgateApi.getInstance(), closeLabel, actionLabel, logger);
    }

    @Override
    public boolean handles(Player player) {
        return this.api.isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public void show(Player player, View view, Response response) {
        Locale locale = player.locale();
        FormPlan plan = FormPlan.of(view, renderer(locale));
        Form form = switch (plan.type()) {
            case MODAL -> modal(plan, response);
            case SIMPLE -> simple(plan, response);
            case CUSTOM -> custom(plan, response);
        };
        if (!this.api.sendForm(player.getUniqueId(), form)) {
            this.logger.fine("Floodgate did not send a form to " + player.getName() + " (no longer a Bedrock connection)");
        }
    }

    private FormPlan.Renderer renderer(Locale locale) {
        return new FormPlan.Renderer() {
            @Override
            public String text(Component component) {
                return BedrockText.render(component, locale);
            }

            @Override
            public String item(Body.Item item) {
                ItemStack stack = item.item();
                StringBuilder line = new StringBuilder(BedrockText.render(stack.effectiveName(), locale));
                if (stack.getAmount() > 1) {
                    line.append(" x").append(stack.getAmount());
                }
                String description = BedrockText.render(item.description(), locale);
                if (!description.isEmpty()) {
                    line.append('\n').append(description);
                }
                return line.toString();
            }

            @Override
            public String closeLabel() {
                return BedrockText.render(FloodgateForms.this.closeLabel.get(), locale);
            }

            @Override
            public String actionLabel() {
                return BedrockText.render(FloodgateForms.this.actionLabel.get(), locale);
            }
        };
    }

    private Form modal(FormPlan plan, Response response) {
        return ModalForm.builder()
            .title(plan.title())
            .content(plan.content())
            .button1(plan.buttons().get(0))
            .button2(plan.buttons().get(1))
            .validResultHandler(result -> answer(plan.clicked(result.clickedButtonId()).orElse(null), response))
            .closedOrInvalidResultHandler(() -> answer(plan.closed().orElse(null), response))
            .build();
    }

    private Form simple(FormPlan plan, Response response) {
        SimpleForm.Builder builder = SimpleForm.builder().title(plan.title()).content(plan.content());
        for (String label : plan.buttons()) {
            builder.button(label);
        }
        return builder
            .validResultHandler(result -> answer(plan.clicked(result.clickedButtonId()).orElse(null), response))
            .closedOrInvalidResultHandler(() -> answer(plan.closed().orElse(null), response))
            .build();
    }

    private Form custom(FormPlan plan, Response response) {
        CustomForm.Builder builder = CustomForm.builder().title(plan.title());
        for (FormPlan.Field field : plan.fields()) {
            switch (field) {
                case FormPlan.Field.Label label -> builder.label(label.label());
                case FormPlan.Field.Text text -> builder.input(text.label(), "", text.initial());
                case FormPlan.Field.Toggle toggle -> builder.toggle(toggle.label(), toggle.initial());
                case FormPlan.Field.Dropdown dropdown -> builder.dropdown(dropdown.label(), dropdown.options(), dropdown.initial());
                case FormPlan.Field.Slider slider -> builder.slider(slider.label(), slider.min(), slider.max(), slider.step(), slider.initial());
            }
        }
        int count = plan.fields().size();
        return builder
            .validResultHandler(result -> answer(plan.submitted(values(result, count)).orElse(null), response))
            .closedOrInvalidResultHandler(() -> answer(plan.closed().orElse(null), response))
            .build();
    }

    /** One raw value per component (null for labels), in component order. */
    private static List<Object> values(CustomFormResponse result, int count) {
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object value;
            try {
                value = result.valueAt(i);
            } catch (IllegalArgumentException | ClassCastException e) {
                value = null;
            }
            values.add(value);
        }
        return values;
    }

    private void answer(FormPlan.Answer answer, Response response) {
        if (answer == null) {
            return;
        }
        try {
            response.answer(answer.button(), answer.values());
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "A Bedrock form answer could not be handled", e);
        }
    }
}

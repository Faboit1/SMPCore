package net.siftvanilla.siftcore.feature.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;

/**
 * The {@code [item]} (or {@code [i]}) tag in chat: the first one in a message is replaced by the name of the item
 * the player holds, which shows the item when hovered. Works on component trees, so a message already changed by
 * other listeners keeps its structure. Pure (Adventure only), thread-safe.
 */
final class ItemTag {

    /** {@code [item]} or {@code [i]}, any case. */
    static final Pattern TAG = Pattern.compile("\\[(?:item|i)]", Pattern.CASE_INSENSITIVE);

    private ItemTag() {
    }

    /** Whether the text contains the tag. */
    static boolean present(String text) {
        return TAG.matcher(text).find();
    }

    /** The message with its first tag replaced; unchanged when it has none. Hover texts are never searched. */
    static Component replaceFirst(Component message, Component replacement) {
        return message.replaceText(TextReplacementConfig.builder()
            .match(TAG)
            .once()
            .replaceInsideHoverEvents(false)
            .replacement(replacement)
            .build());
    }

    /**
     * The plain text of a message without the items shown in it: parts that show an item on hover (an {@code [item]}
     * replacement) are left out, so an item's name never counts as something the player typed (a mention, say).
     */
    static String typedText(Component message) {
        StringBuilder sb = new StringBuilder();
        appendTyped(message, sb);
        return sb.toString();
    }

    private static void appendTyped(Component component, StringBuilder sb) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM) {
            return;
        }
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        } else {
            sb.append(ChatText.plain(component.children(List.of())));
        }
        for (Component child : component.children()) {
            appendTyped(child, sb);
        }
    }

    /**
     * A copy of the component with colours and decorations removed everywhere (its children and the arguments of
     * translations too), so an item's name (rarity colour, italic custom names) shows in the chat colour. Text,
     * translation keys and fallbacks are kept, so clients still show the name in their own language.
     */
    static Component monochrome(Component component) {
        Component result = component.style(Style.empty());
        if (result instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
            List<Component> arguments = new ArrayList<>(translatable.arguments().size());
            for (TranslationArgument argument : translatable.arguments()) {
                arguments.add(monochrome(argument.asComponent()));
            }
            result = translatable.arguments(arguments);
        }
        if (component.children().isEmpty()) {
            return result;
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(monochrome(child));
        }
        return result.children(children);
    }
}

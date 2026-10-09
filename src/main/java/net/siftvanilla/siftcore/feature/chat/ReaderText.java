package net.siftvanilla.siftcore.feature.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The per-reader versions of a message's text: the reader's own name made to stand out ({@code mention-highlight})
 * and milder words hidden for readers who turned the strict filter on ({@code chat-filter-strict}). Both work on the
 * typed parts of a component tree only: parts showing an item ({@code [item]}) are left exactly as they are. Styles
 * are kept, so a chat colour painted afterwards still covers every part. Pure (Adventure only), thread-safe.
 */
final class ReaderText {

    private ReaderText() {
    }

    /**
     * The message with every mention of {@code names} (as {@link Mentions#matches} finds them) decorated; the
     * message itself when nothing matches or {@code decoration} is null.
     */
    static Component highlight(Component message, Collection<String> names, boolean plainNames, int minPlainLength,
                               TextDecoration decoration) {
        if (decoration == null || names.isEmpty()) {
            return message;
        }
        return walk(message, text -> {
            List<Mentions.Match> matches = Mentions.matches(text.content(), names, plainNames, minPlainLength);
            if (matches.isEmpty()) {
                return null;
            }
            List<Component> parts = new ArrayList<>(matches.size() * 2 + 1);
            int at = 0;
            for (Mentions.Match match : matches) {
                if (match.start() > at) {
                    parts.add(Component.text(text.content().substring(at, match.start())));
                }
                parts.add(Component.text(text.content().substring(match.start(), match.end())).decoration(decoration, true));
                at = match.end();
            }
            if (at < text.content().length()) {
                parts.add(Component.text(text.content().substring(at)));
            }
            return parts;
        });
    }

    /** The message with {@code filter} applied to the text of every typed part. */
    static Component filterTyped(Component message, UnaryOperator<String> filter) {
        return walk(message, text -> {
            String filtered = filter.apply(text.content());
            return filtered.equals(text.content()) ? null : List.of(Component.text(filtered));
        });
    }

    /** Replaces a text part's own content with new parts; null keeps it. */
    @FunctionalInterface
    private interface Split {
        List<Component> parts(TextComponent text);
    }

    private static Component walk(Component component, Split split) {
        if (showsItem(component)) {
            return component;
        }
        Component result = component;
        List<Component> replaced = null;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            replaced = split.parts(text);
            if (replaced != null) {
                result = text.content("");
            }
        }
        boolean changed = replaced != null;
        List<Component> children = new ArrayList<>();
        if (replaced != null) {
            children.addAll(replaced);
        }
        for (Component child : component.children()) {
            Component walked = walk(child, split);
            changed |= walked != child;
            children.add(walked);
        }
        return changed ? result.children(children) : component;
    }

    private static boolean showsItem(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        return hover != null && hover.action() == HoverEvent.Action.SHOW_ITEM;
    }
}

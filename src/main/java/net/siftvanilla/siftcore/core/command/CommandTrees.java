package net.siftvanilla.siftcore.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.function.UnaryOperator;

/** Rewrites built Brigadier trees (nodes are immutable once built, so a rewrite is a copy). */
final class CommandTrees {

    private CommandTrees() {
    }

    /**
     * A copy of the tree with every node's command passed through {@code wrapper}. Everything else is kept as it is:
     * names, argument types, suggestions, requirements and redirects (a redirect still points at its original target,
     * whose commands are not wrapped; SiftCore's trees have none).
     */
    @SuppressWarnings("unchecked")
    static <S> LiteralCommandNode<S> wrapCommands(LiteralCommandNode<S> root, UnaryOperator<Command<S>> wrapper) {
        return (LiteralCommandNode<S>) copy(root, wrapper);
    }

    private static <S> CommandNode<S> copy(CommandNode<S> node, UnaryOperator<Command<S>> wrapper) {
        ArgumentBuilder<S, ?> builder = node.createBuilder();
        if (node.getCommand() != null) {
            builder.executes(wrapper.apply(node.getCommand()));
        }
        if (node.getRedirect() == null) {
            for (CommandNode<S> child : node.getChildren()) {
                builder.then(copy(child, wrapper));
            }
        }
        return builder.build();
    }
}

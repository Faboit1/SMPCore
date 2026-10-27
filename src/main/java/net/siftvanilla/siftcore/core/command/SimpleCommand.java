package net.siftvanilla.siftcore.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.function.Function;

/** A {@link SiftCommand} defined by a function from label to Brigadier tree. */
public record SimpleCommand(String name, List<String> aliases, String description, String permission,
                            Function<String, LiteralArgumentBuilder<CommandSourceStack>> tree) implements SiftCommand {

    public SimpleCommand {
        aliases = List.copyOf(aliases);
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String label) {
        return this.tree.apply(label);
    }
}

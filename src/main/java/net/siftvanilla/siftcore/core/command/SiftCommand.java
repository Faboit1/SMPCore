package net.siftvanilla.siftcore.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;

/**
 * A command contributed by a feature. {@link #build(String)} returns the full Brigadier tree for the given label;
 * permissions are expressed with {@code requires(...)} so players only see what they may use.
 */
public interface SiftCommand {

    /** The primary label, also the key in {@code commands.yml}. */
    String name();

    /** Default aliases; {@code commands.yml} can replace them. */
    List<String> aliases();

    /** One-line description shown in /help. */
    String description();

    /** The permission needed to see and run the root command, or null for everyone. */
    String permission();

    LiteralArgumentBuilder<CommandSourceStack> build(String label);
}

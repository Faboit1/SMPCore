package net.siftvanilla.siftcore.core.link;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Cosmetic rank perks as other features show them: nicknames, chat tags, chat colours, rank join and leave lines and
 * kill effects. Implemented by the cosmetics feature; consumed by chat (public chat, private messages, mentions),
 * combat (death messages, kill streaks, kill effects) and the join/leave messages of the extras feature.
 * <p>
 * Every method is cheap and safe from any thread (the async chat thread included): answers come from in-memory
 * caches and permission checks. A player who lost the permission for a perk (an expired rank) silently gets the
 * default; their stored choice is kept for when they have it again. {@link #NONE} is used when the feature is off.
 */
public interface Cosmetics {

    /** No cosmetics: plain names, no tags, no colours, default join lines, no kill effects. */
    Cosmetics NONE = new Cosmetics() {
    };

    /** Cosmetics that look up {@code cosmetics} on every call, for features built before the cosmetics feature. */
    static Cosmetics late(Supplier<Cosmetics> cosmetics) {
        return new Cosmetics() {
            @Override
            public Component name(Player player) {
                return cosmetics.get().name(player);
            }

            @Override
            public Component name(UUID player, String name) {
                return cosmetics.get().name(player, name);
            }

            @Override
            public String nick(Player player) {
                return cosmetics.get().nick(player);
            }

            @Override
            public Optional<Player> byNick(String nick) {
                return cosmetics.get().byNick(nick);
            }

            @Override
            public Component tag(Player player) {
                return cosmetics.get().tag(player);
            }

            @Override
            public Component paint(Player sender, Component message) {
                return cosmetics.get().paint(sender, message);
            }

            @Override
            public boolean showsChatColours(UUID viewer) {
                return cosmetics.get().showsChatColours(viewer);
            }

            @Override
            public Component joinLine(Player player) {
                return cosmetics.get().joinLine(player);
            }

            @Override
            public Component quitLine(Player player) {
                return cosmetics.get().quitLine(player);
            }

            @Override
            public void kill(Player killer, Player victim, Location at) {
                cosmetics.get().kill(killer, victim, at);
            }
        };
    }

    /**
     * The name shown for an online player: their nickname in its colour or gradient (hovering it shows the real
     * name) when they may use one, otherwise their name as plain text.
     */
    default Component name(Player player) {
        return Component.text(player.getName());
    }

    /** The same for a player who may be offline: online players get {@link #name(Player)}, others {@code name}. */
    default Component name(UUID player, String name) {
        return Component.text(name);
    }

    /** The nickname a player shows right now (plain text), or null when they show their name. */
    default String nick(Player player) {
        return null;
    }

    /** The online player who shows this nickname (ignoring case), so {@code /msg <nick>} reaches them. */
    default Optional<Player> byNick(String nick) {
        return Optional.empty();
    }

    /** The chat tag shown before the player's name in public chat, or null without one. */
    default Component tag(Player player) {
        return null;
    }

    /**
     * The player's message in their chat colour or gradient. Items shown in it ({@code [item]}) keep their own look;
     * a player without a chat colour gets {@code message} back unchanged.
     */
    default Component paint(Player sender, Component message) {
        return message;
    }

    /** Whether a viewer wants to see the chat colours other players chose (a player setting). */
    default boolean showsChatColours(UUID viewer) {
        return true;
    }

    /** The line announcing that a player joined, or null to use the default join message. */
    default Component joinLine(Player player) {
        return null;
    }

    /** The line announcing that a player left, or null to use the default leave message. */
    default Component quitLine(Player player) {
        return null;
    }

    /**
     * A player killed another: plays the killer's kill effect where the victim died. Call on the thread that owns
     * {@code at} (the victim's region thread in the death event).
     */
    default void kill(Player killer, Player victim, Location at) {
    }
}

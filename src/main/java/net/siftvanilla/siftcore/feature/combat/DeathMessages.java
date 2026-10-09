package net.siftvanilla.siftcore.feature.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.Style;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Builds and sends death messages, combat log announcements and kill streak lines, each to the players whose setting
 * shows it ({@code death-messages}, {@code combat-log-announcements}, {@code kill-streak-announcements}). Player kills
 * get a clean line ("Alex was killed by Sam using Diamond Sword", the weapon shows the item on hover); every other
 * death keeps the game's own message, shown in the secondary colour. Players are named as they show themselves (a
 * nickname in its colour, the real name on hover). The victim and the killer always hear about their own death and
 * kill, and a vanished player's name only reaches players who can see them.
 */
final class DeathMessages {

    private final Lang lang;
    private final PlayerSettings settings;
    private final Relations relations;
    private final Participants participants;
    private final Cosmetics cosmetics;

    DeathMessages(Lang lang, PlayerSettings settings, Relations relations, Participants participants, Cosmetics cosmetics) {
        this.lang = lang;
        this.settings = settings;
        this.relations = relations;
        this.participants = participants;
        this.cosmetics = cosmetics;
    }

    /** A player's name as they show it (their nickname, or their last known name when offline). */
    Component name(UUID player, String name) {
        return this.cosmetics.name(player, name);
    }

    /** The line for a player kill; the weapon is left out when null. */
    Component kill(Component victim, Component killer, ItemStack weapon) {
        if (weapon == null) {
            return this.lang.get(CombatMessages.DEATH_KILLED, Arg.component("victim", victim), Arg.component("killer", killer));
        }
        return this.lang.get(CombatMessages.DEATH_KILLED_USING, Arg.component("victim", victim), Arg.component("killer", killer),
            Arg.component("item", itemName(weapon)));
    }

    /** The line for a player who left in combat, with the player who gets the kill when there is one. */
    Component logout(Component name, Component killer) {
        if (killer == null) {
            return this.lang.get(CombatMessages.LOGOUT_ANNOUNCE, Arg.component("name", name));
        }
        return this.lang.get(CombatMessages.LOGOUT_ANNOUNCE_KILLED, Arg.component("name", name), Arg.component("killer", killer));
    }

    /** "Alex is on a kill streak of 10." */
    Component streak(Component name, int streak) {
        return this.lang.get(CombatMessages.STREAK_REACHED, Arg.component("name", name), Arg.number("count", streak));
    }

    /** "Sam ended Alex's kill streak of 12." */
    Component streakEnded(Component killer, Component victim, int streak) {
        return this.lang.get(CombatMessages.STREAK_ENDED, Arg.component("killer", killer), Arg.component("victim", victim),
            Arg.number("count", streak));
    }

    /** The game's own death message in the secondary colour (its translation, arguments and hovers are kept). */
    Component restyle(Component vanilla) {
        return monochrome(vanilla).color(this.lang.style().palette().secondary());
    }

    /**
     * Sends a death message ("Alex was killed by Sam", or the game's own line) to the players whose
     * {@code death-messages} choice shows it: all deaths, player kills only, or deaths and kills of their friends and
     * teammates. {@code killer} is null for a death without a player.
     */
    void death(Component message, UUID victim, UUID killer) {
        boolean pvp = killer != null;
        send(message, victim, killer, viewer -> {
            DeathFilter filter = this.settings.get(viewer, CombatFeature.DEATH_MESSAGES);
            return filter.shows(pvp, filter == DeathFilter.FRIENDS_TEAM && related(this.relations, viewer, victim, killer));
        });
    }

    /** Sends "Alex logged out in combat" to the players who keep {@code combat-log-announcements} on. */
    void logoutLine(Component message, UUID victim, UUID killer) {
        send(message, victim, killer, viewer -> this.settings.get(viewer, CombatFeature.LOG_ANNOUNCEMENTS));
    }

    /** Sends a kill streak line to the players who keep {@code kill-streak-announcements} on. */
    void streakLine(Component message, UUID victim, UUID killer) {
        send(message, victim, killer, viewer -> this.settings.get(viewer, CombatFeature.STREAK_ANNOUNCEMENTS));
    }

    /** Whether the victim or the killer (null for none) is a friend or teammate of the viewer. */
    static boolean related(Relations relations, UUID viewer, UUID victim, UUID killer) {
        return relations.allows(Audience.FRIENDS_TEAM, viewer, victim) || (killer != null && relations.allows(Audience.FRIENDS_TEAM, viewer, killer));
    }

    /**
     * Sends a line about a death to the victim and the killer either way, to every other online player {@code wants}
     * says yes for, and to the console. Players who can't see a vanished victim or killer never get the line.
     */
    private void send(Component message, UUID victim, UUID killer, Predicate<UUID> wants) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID id = online.getUniqueId();
            boolean involved = id.equals(victim) || id.equals(killer);
            if (!involved && (!this.participants.visibleTo(online, victim) || !this.participants.visibleTo(online, killer))) {
                continue;
            }
            if (involved || wants.test(id)) {
                online.sendMessage(message);
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** The item's name without colours or italics, showing the item on hover. */
    static Component itemName(ItemStack item) {
        return unstyled(item.effectiveName()).hoverEvent(item.asHoverEvent());
    }

    /** A copy of the component with every style removed, keeping its text, translations and arguments. */
    static Component unstyled(Component component) {
        return restyled(component, false);
    }

    /**
     * A copy of the component without colours and decorations (bold, italics, obfuscation...), keeping its text,
     * translations, arguments, hover and click events, so it can be shown in one colour.
     */
    static Component monochrome(Component component) {
        return restyled(component, true);
    }

    private static Component restyled(Component component, boolean keepEvents) {
        Style style = component.style();
        Style kept = keepEvents
            ? Style.style().hoverEvent(style.hoverEvent()).clickEvent(style.clickEvent()).insertion(style.insertion()).build()
            : Style.empty();
        Component result = component.style(kept);
        if (result instanceof TranslatableComponent translatable && !translatable.arguments().isEmpty()) {
            List<Component> arguments = new ArrayList<>(translatable.arguments().size());
            for (TranslationArgument argument : translatable.arguments()) {
                arguments.add(restyled(argument.asComponent(), keepEvents));
            }
            result = translatable.arguments(arguments);
        }
        if (component.children().isEmpty()) {
            return result;
        }
        List<Component> children = new ArrayList<>(component.children().size());
        for (Component child : component.children()) {
            children.add(restyled(child, keepEvents));
        }
        return result.children(children);
    }
}

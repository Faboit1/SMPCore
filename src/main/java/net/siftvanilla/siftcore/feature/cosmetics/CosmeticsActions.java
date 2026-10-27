package net.siftvanilla.siftcore.feature.cosmetics;

import java.time.Duration;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.Services;
import net.siftvanilla.siftcore.core.teleport.CombatStatus;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.entity.Player;

/**
 * Every change a player (or staff) makes to cosmetics, checked the same way whether it comes from a command or a
 * dialog: the feature is on, the player is not in combat (every perk is refused in combat), they have the
 * permission, and the choice passes its rules. Nothing is trusted from the client: a dialog button only names a
 * choice, and the choice is checked again here. Runs on the player's thread (commands and dialog handlers).
 */
final class CosmeticsActions {

    /** What happened: success or the reason, with the message to show. */
    record Outcome(boolean ok, MessageKey key, Arg... args) {

        static Outcome ok(MessageKey key, Arg... args) {
            return new Outcome(true, key, args);
        }

        static Outcome refused(MessageKey key, Arg... args) {
            return new Outcome(false, key, args);
        }
    }

    private final Services services;
    private final Lang lang;
    private final CosmeticsService cosmetics;
    private final CombatStatus combat;

    CosmeticsActions(Services services, CosmeticsService cosmetics, CombatStatus combat) {
        this.services = services;
        this.lang = services.lang();
        this.cosmetics = cosmetics;
        this.combat = combat;
    }

    /** Why the player can't change cosmetics right now, or null when they can. */
    Outcome blocked(Player player) {
        if (!this.cosmetics.on()) {
            return Outcome.refused(CosmeticsMessages.DISABLED);
        }
        Duration left = this.combat.remaining(player.getUniqueId());
        if (!left.isZero()) {
            return Outcome.refused(CosmeticsMessages.IN_COMBAT, Arg.time("time", Duration.ofSeconds(Math.max(1, (left.toMillis() + 999) / 1000))));
        }
        return null;
    }

    /** The "comes with the ... rank" refusal. */
    Outcome locked(MessageKey rank) {
        return Outcome.refused(CosmeticsMessages.LOCKED, Arg.component("unlock", this.lang.get(rank)));
    }

    /** Sends an outcome on its channel (action bar for both success and refusal). */
    void tell(Player player, Outcome outcome) {
        this.services.messenger().send(player, outcome.key(), outcome.args());
    }

    // ------------------------------------------------------------------ styles

    /**
     * Checks a style for chat ({@code nick} false) or a nickname: permission (vanilla colours from the configured list,
     * hex colours and gradients for the premium node) and the colour rules. Null when it may be used.
     */
    Outcome checkStyle(Player player, ChatStyle style, boolean nick) {
        if (style.none()) {
            return null;
        }
        String basic = nick ? CosmeticsNodes.NICK : CosmeticsNodes.CHAT_COLOR;
        String premium = nick ? CosmeticsNodes.NICK_GRADIENT : CosmeticsNodes.CHAT_COLOR_HEX;
        if (style.premium() || !this.cosmetics.settings().colors().basic().contains(style.first())) {
            if (!player.hasPermission(premium)) {
                return locked(CosmeticsMessages.RANK_TYCOON);
            }
        } else if (!player.hasPermission(basic) && !player.hasPermission(premium)) {
            return locked(CosmeticsMessages.RANK_BARON);
        }
        ColorRules.Verdict verdict = this.cosmetics.rules().check(style);
        if (verdict.allowed()) {
            return null;
        }
        if (verdict.tooDark()) {
            return Outcome.refused(CosmeticsMessages.COLOR_TOO_DARK);
        }
        return Outcome.refused(switch (verdict.reserved()) {
            case ERRORS -> CosmeticsMessages.COLOR_RESERVED_ERRORS;
            case MONEY -> CosmeticsMessages.COLOR_RESERVED_MONEY;
            case SERVER -> CosmeticsMessages.COLOR_RESERVED_SERVER;
        });
    }

    /** Parses typed colours ({@code from} and an optional {@code to} for a gradient); null when not colours. */
    static ChatStyle typed(String from, String to) {
        String first = from == null ? "" : from.strip();
        String second = to == null ? "" : to.strip();
        if (first.isEmpty()) {
            return null;
        }
        ChatStyle style = ChatStyle.parse(second.isEmpty() ? first : first + ":" + second);
        return style == null || style.none() ? null : style;
    }

    // ------------------------------------------------------------------ chat colour

    Outcome setChatStyle(Player player, ChatStyle style) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!player.hasPermission(CosmeticsNodes.CHAT_COLOR) && !player.hasPermission(CosmeticsNodes.CHAT_COLOR_HEX)) {
            return locked(CosmeticsMessages.RANK_BARON);
        }
        if (style.none()) {
            this.cosmetics.profiles().update(player.getUniqueId(), profile -> profile.withChatStyle(ChatStyle.NONE));
            return Outcome.ok(CosmeticsMessages.COLOR_CLEARED);
        }
        Outcome refused = checkStyle(player, style, false);
        if (refused != null) {
            return refused;
        }
        this.cosmetics.profiles().update(player.getUniqueId(), profile -> profile.withChatStyle(style));
        return Outcome.ok(CosmeticsMessages.COLOR_SET, Arg.component("preview", this.cosmetics.styleName(style)));
    }

    // ------------------------------------------------------------------ nicknames

    /**
     * Checks a nickname for {@code owner}; null when it may be used. A nickname another player stored only counts as
     * taken while they hold it ({@link CosmeticsService#holdsNick}).
     */
    Outcome checkNick(UUID owner, String nick) {
        CosmeticsSettings.Nicknames rules = this.cosmetics.settings().nicknames();
        NickRules.Verdict verdict = rules.rules().check(owner, nick, this.services.directory()::uuid, this.cosmetics::nickHolder,
            this.cosmetics.checks()::filtered);
        return switch (verdict.problem()) {
            case OK -> null;
            case LENGTH -> Outcome.refused(CosmeticsMessages.NICK_LENGTH, Arg.number("min", rules.minLength()), Arg.number("max", rules.maxLength()));
            case CHARACTERS -> Outcome.refused(CosmeticsMessages.NICK_CHARACTERS);
            case RESERVED -> Outcome.refused(CosmeticsMessages.NICK_RESERVED, Arg.text("word", verdict.word()));
            case PLAYER_NAME -> Outcome.refused(CosmeticsMessages.NICK_PLAYER_NAME);
            case TAKEN -> Outcome.refused(CosmeticsMessages.NICK_TAKEN);
            case FILTERED -> Outcome.refused(CosmeticsMessages.NICK_FILTERED);
        };
    }

    /** A player sets their own nickname ({@code style} null keeps the current colour). */
    Outcome setNick(Player player, String nick, ChatStyle style) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!player.hasPermission(CosmeticsNodes.NICK)) {
            return locked(CosmeticsMessages.RANK_BARON);
        }
        ChatStyle wanted = style == null ? this.cosmetics.profiles().get(player.getUniqueId()).nickStyle() : style;
        if (style != null) {
            Outcome refusedStyle = checkStyle(player, wanted, true);
            if (refusedStyle != null) {
                return refusedStyle;
            }
        }
        Outcome refused = checkNick(player.getUniqueId(), nick);
        if (refused != null) {
            return refused;
        }
        Profile current = this.cosmetics.profiles().get(player.getUniqueId());
        boolean renamed = !nick.equals(current.nick());
        Duration cooldown = this.cosmetics.settings().nicknames().cooldown();
        if (renamed && !cooldown.isZero()) {
            Duration left = this.services.cooldowns().remaining(player.getUniqueId(), "cosmetics-nick");
            if (!left.isZero()) {
                return Outcome.refused(CosmeticsMessages.NICK_COOLDOWN, Arg.time("time", Duration.ofSeconds(Math.max(1, (left.toMillis() + 999) / 1000))));
            }
        }
        if (!this.cosmetics.claimNick(player.getUniqueId(), nick, wanted, player.getUniqueId().toString())) {
            return Outcome.refused(CosmeticsMessages.NICK_TAKEN);
        }
        if (renamed && !cooldown.isZero()) {
            this.services.cooldowns().start(player.getUniqueId(), "cosmetics-nick", cooldown);
        }
        return Outcome.ok(CosmeticsMessages.NICK_SET, Arg.component("nick", this.cosmetics.name(player)));
    }

    Outcome clearNick(Player player) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (this.cosmetics.profiles().get(player.getUniqueId()).nick() == null) {
            return Outcome.refused(CosmeticsMessages.NICK_NOT_SET);
        }
        this.cosmetics.claimNick(player.getUniqueId(), null, null, player.getUniqueId().toString());
        return Outcome.ok(CosmeticsMessages.NICK_REMOVED);
    }

    // ------------------------------------------------------------------ tags

    Outcome setTag(Player player, String id) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        ChatTag tag = id == null ? null : this.cosmetics.settings().tags().get(id);
        if (tag == null) {
            return Outcome.refused(CosmeticsMessages.TAG_UNKNOWN, Arg.text("id", id == null ? "" : id));
        }
        if (!this.cosmetics.usable(player, tag)) {
            return Outcome.refused(CosmeticsMessages.TAG_LOCKED, Arg.component("unlock", unlock(tag)));
        }
        boolean claim = tag.claimable(CosmeticsService.month())
            && !this.cosmetics.profiles().get(player.getUniqueId()).ownedTags().contains(tag.id());
        this.cosmetics.profiles().update(player.getUniqueId(), profile -> {
            Profile changed = profile.withTag(tag.id());
            return claim ? changed.withOwnedTag(tag.id()) : changed;
        });
        return claim ? Outcome.ok(CosmeticsMessages.TAG_CLAIMED, Arg.component("tag", tag.display()))
            : Outcome.ok(CosmeticsMessages.TAG_SET, Arg.component("tag", tag.display()));
    }

    Outcome clearTag(Player player) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (this.cosmetics.profiles().get(player.getUniqueId()).tag() == null) {
            return Outcome.refused(CosmeticsMessages.TAG_NOT_SET);
        }
        this.cosmetics.profiles().update(player.getUniqueId(), profile -> profile.withTag(null));
        return Outcome.ok(CosmeticsMessages.TAG_REMOVED);
    }

    /** What unlocks a tag, for locked tags. */
    Component unlock(ChatTag tag) {
        return tag.hint().isEmpty() ? this.lang.get(CosmeticsMessages.HIGHER_RANK) : Component.text(tag.hint());
    }

    // ------------------------------------------------------------------ join and leave messages

    /** Checks a custom message; null when fine. */
    Outcome checkMessage(String message) {
        int max = this.cosmetics.settings().join().maxLength();
        return switch (JoinText.check(message, max, this.cosmetics.checks()::link, this.cosmetics.checks()::filtered)) {
            case OK -> null;
            case EMPTY -> Outcome.refused(CosmeticsMessages.JOINMSG_EMPTY);
            case TOO_LONG -> Outcome.refused(CosmeticsMessages.JOINMSG_TOO_LONG, Arg.number("max", max));
            case CHARACTERS -> Outcome.refused(CosmeticsMessages.JOINMSG_CHARACTERS);
            case TOKENS -> Outcome.refused(CosmeticsMessages.JOINMSG_TOKENS);
            case LINK -> Outcome.refused(CosmeticsMessages.JOINMSG_LINK);
            case FILTERED -> Outcome.refused(CosmeticsMessages.JOINMSG_FILTERED);
        };
    }

    /** Refusal for custom join messages (feature, combat, permission), or null. */
    Outcome customBlocked(Player player) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!this.cosmetics.settings().join().enabled()) {
            return Outcome.refused(CosmeticsMessages.JOINMSG_OFF);
        }
        return player.hasPermission(CosmeticsNodes.JOIN_CUSTOM) ? null : locked(CosmeticsMessages.RANK_TYCOON);
    }

    /** Sets ({@code text} not null) or resets a custom join or leave message. */
    Outcome setMessage(Player player, boolean join, String text) {
        Outcome blocked = customBlocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (text == null) {
            this.cosmetics.profiles().update(player.getUniqueId(), profile -> join ? profile.withJoinMessage(null) : profile.withLeaveMessage(null));
            return Outcome.ok(join ? CosmeticsMessages.JOINMSG_JOIN_RESET : CosmeticsMessages.JOINMSG_LEAVE_RESET);
        }
        String cleaned = JoinText.clean(text);
        Outcome refused = checkMessage(cleaned);
        if (refused != null) {
            return refused;
        }
        this.cosmetics.profiles().update(player.getUniqueId(), profile -> join ? profile.withJoinMessage(cleaned) : profile.withLeaveMessage(cleaned));
        return Outcome.ok(join ? CosmeticsMessages.JOINMSG_JOIN_SET : CosmeticsMessages.JOINMSG_LEAVE_SET);
    }

    /** Shows the player their join and leave lines as everyone would see them. */
    void preview(Player player) {
        this.services.messenger().send(player, CosmeticsMessages.JOINMSG_PREVIEW_HEADER);
        player.sendMessage(this.cosmetics.render(player, true));
        player.sendMessage(this.cosmetics.render(player, false));
    }

    // ------------------------------------------------------------------ kill effects

    Outcome setKillEffect(Player player, KillEffect effect) {
        Outcome blocked = blocked(player);
        if (blocked != null) {
            return blocked;
        }
        if (!this.cosmetics.settings().killEffects().enabled()) {
            return Outcome.refused(CosmeticsMessages.KILL_OFF);
        }
        if (effect == null) {
            this.cosmetics.profiles().update(player.getUniqueId(), profile -> profile.withKillEffect(null));
            return Outcome.ok(CosmeticsMessages.KILL_CLEARED);
        }
        if (!this.cosmetics.usable(player, effect)) {
            return locked(CosmeticsMessages.RANK_TYCOON);
        }
        this.cosmetics.profiles().update(player.getUniqueId(), profile -> profile.withKillEffect(effect.id()));
        this.cosmetics.preview(player, effect);
        return Outcome.ok(CosmeticsMessages.KILL_SET, Arg.component("effect", this.lang.get(CosmeticsMessages.name(effect))));
    }
}

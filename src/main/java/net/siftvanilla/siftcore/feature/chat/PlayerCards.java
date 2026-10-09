package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.Cosmetics;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.Ledger;

/**
 * Player names as they appear in chat and private messages: the name (or the nickname the player shows, in its
 * colour), a card shown on hover (real name for nicknames, rank, team, balance, kills, playtime) and a click. In
 * public chat the click opens the player's profile ({@code /profile <name>}: friend actions, message, teleport
 * request, team invite, pay, stats) for readers who may use {@code /profile} while the server has SiftCore's profiles,
 * and shift-click puts {@code /msg <name> } in the chat box; for other readers the click starts a private message.
 * The balance line follows the player's {@code balance-privacy}. The caller builds the name each kind of reader needs
 * and hands it to them.
 * <p>
 * Everything is read from thread-safe in-memory sources (rank labels, cosmetics, the team registry, the ledger, the
 * stats store), so it is safe on the async chat thread.
 */
final class PlayerCards {

    /** Who may open profiles ({@code /profile}, declared by the friends feature). */
    static final String PROFILE_PERMISSION = "siftcore.command.profile";

    private final Lang lang;
    private final Ranks ranks;
    private final TeamLookup teams;
    private final Ledger ledger;
    private final StatsRecorder stats;
    private final Cosmetics cosmetics;
    private final Supplier<String> profileCommand;

    /**
     * @param profileCommand what a click on a name runs to open a profile, before the name ({@code "/profile "}, or
     *                       the namespaced form while another plugin holds {@code /profile}); null while SiftCore has
     *                       no profiles (friends off, {@code /profile} turned off in {@code commands.yml})
     */
    PlayerCards(Lang lang, Ranks ranks, TeamLookup teams, Ledger ledger, StatsRecorder stats, Cosmetics cosmetics,
                Supplier<String> profileCommand) {
        this.lang = lang;
        this.ranks = ranks;
        this.teams = teams;
        this.ledger = ledger;
        this.stats = stats;
        this.cosmetics = cosmetics;
        this.profileCommand = profileCommand;
    }

    /** The player's rank label, empty when they have none. */
    String rank(UUID player) {
        String label = this.ranks.label(player);
        return label == null ? "" : ChatText.clean(label);
    }

    /**
     * The player's rank as shown in chat: in its LuckPerms colour or gradient when it has one, otherwise plain. Null
     * when they have no rank label. Safe on the async chat thread (labels are cached).
     */
    Component rankComponent(UUID player, org.bukkit.entity.Player online) {
        String label = rank(player);
        if (label.isEmpty()) {
            return null;
        }
        Component styled = online == null ? null : this.ranks.component(online);
        return styled == null || ChatText.plain(styled).isBlank() ? Component.text(label) : styled;
    }

    /** What a click on a name in public chat runs to open a profile (followed by the name), or null without profiles. */
    String profileCommand() {
        return this.profileCommand.get();
    }

    /**
     * The hover card of a player: the name they show, their real name when that is a nickname, then their stats and
     * what clicking does.
     *
     * @param balance whether the card shows the balance (the reader may see it)
     * @param profile whether clicking opens the profile (else it starts a private message)
     */
    Component card(UUID player, String name, boolean balance, boolean profile) {
        List<Component> lines = new ArrayList<>();
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(player);
        String nick = online == null ? null : this.cosmetics.nick(online);
        if (nick == null) {
            lines.add(this.lang.get(ChatMessages.CARD_NAME, Arg.text("name", name)));
        } else {
            lines.add(this.lang.get(ChatMessages.CARD_NAME_STYLED, Arg.component("name", shown(online))));
            lines.add(this.lang.get(ChatMessages.CARD_REAL_NAME, Arg.text("name", name)));
        }
        Component rank = rankComponent(player, online);
        if (rank != null) {
            lines.add(this.lang.get(ChatMessages.CARD_RANK, Arg.component("rank", rank)));
        }
        Optional<String> team = this.teams.teamName(player);
        lines.add(team.isPresent()
            ? this.lang.get(ChatMessages.CARD_TEAM, Arg.text("team", team.get()))
            : this.lang.get(ChatMessages.CARD_NO_TEAM));
        if (balance) {
            lines.add(this.lang.get(ChatMessages.CARD_BALANCE, Arg.money("balance", this.ledger.balance(player, Currency.MONEY))));
        }
        lines.add(this.lang.get(ChatMessages.CARD_KILLS, Arg.number("kills", this.stats.get(player, StatsRecorder.Stat.KILLS))));
        lines.add(this.lang.get(ChatMessages.CARD_PLAYTIME,
            Arg.time("playtime", Duration.ofSeconds(Math.max(0, this.stats.get(player, StatsRecorder.Stat.PLAYTIME_SECONDS))))));
        lines.add(this.lang.get(profile ? ChatMessages.CARD_CLICK_PROFILE : ChatMessages.CARD_CLICK, Arg.text("name", name)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** The name a player shows (their nickname in its colour, or their name), without hover or click. */
    private Component shown(org.bukkit.entity.Player player) {
        return this.cosmetics.name(player).hoverEvent(null);
    }

    /**
     * A name in public chat: the name the player shows, the card on hover (when enabled, otherwise just the real name
     * of a nickname), and a click that runs {@code profileCommand} with their real name (shift-click:
     * {@code /msg <real name> } in the chat box), or suggests {@code /msg <real name> } when {@code profileCommand} is
     * null (no profiles, or a reader who may not open them).
     *
     * @param balance whether the card shows the balance
     */
    Component chatName(org.bukkit.entity.Player player, boolean withCard, boolean balance, String profileCommand) {
        String name = player.getName();
        Component text = withCard ? shown(player) : this.cosmetics.name(player);
        if (profileCommand != null) {
            text = text.clickEvent(ClickEvent.runCommand(profileCommand + name)).insertion("/msg " + name + " ");
        } else {
            text = text.clickEvent(ClickEvent.suggestCommand("/msg " + name + " "));
        }
        return withCard ? text.hoverEvent(HoverEvent.showText(card(player.getUniqueId(), name, balance, profileCommand != null))) : text;
    }

    /** A name in public chat, with or without a chat tag before it ({@link #chatName}). */
    Component taggedName(org.bukkit.entity.Player player, boolean withCard, boolean balance, String profileCommand) {
        Component name = chatName(player, withCard, balance, profileCommand);
        Component tag = this.cosmetics.tag(player);
        return tag == null ? name : this.lang.get(ChatMessages.TAGGED_NAME, Arg.component("tag", tag), Arg.component("name", name));
    }

    /**
     * A name in a private message: the name the player shows; clicking it starts another message to that player (or
     * a reply to the console), hovering it says so and gives the real name of a nickname.
     */
    Component messageName(String name, org.bukkit.entity.Player player, boolean console) {
        String nick = player == null ? null : this.cosmetics.nick(player);
        Component hover = this.lang.get(ChatMessages.PM_NAME_HOVER, Arg.text("name", nick == null ? name : nick));
        if (nick != null) {
            hover = Component.join(JoinConfiguration.newlines(), hover, this.lang.get(ChatMessages.CARD_REAL_NAME, Arg.text("name", name)));
        }
        Component text = player == null ? Component.text(name) : shown(player);
        return text.clickEvent(ClickEvent.suggestCommand(console ? "/r " : "/msg " + name + " ")).hoverEvent(HoverEvent.showText(hover));
    }
}

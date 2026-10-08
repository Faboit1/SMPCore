package net.siftvanilla.siftcore.feature.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.integration.Ranks;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.TeamLookup;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.economy.Ledger;

/**
 * Player names as they appear in chat and private messages: the name, a card shown on hover (rank, team, balance,
 * kills, playtime) and a click that starts a private message. Everything is read from thread-safe in-memory
 * sources (rank labels, the team registry, the ledger, the stats store), so it is safe on the async chat thread.
 */
final class PlayerCards {

    private final Lang lang;
    private final Ranks ranks;
    private final TeamLookup teams;
    private final Ledger ledger;
    private final StatsRecorder stats;

    PlayerCards(Lang lang, Ranks ranks, TeamLookup teams, Ledger ledger, StatsRecorder stats) {
        this.lang = lang;
        this.ranks = ranks;
        this.teams = teams;
        this.ledger = ledger;
        this.stats = stats;
    }

    /** The player's rank label, empty when they have none. */
    String rank(UUID player) {
        String label = this.ranks.label(player);
        return label == null ? "" : ChatText.clean(label);
    }

    /** The hover card of a player. */
    Component card(UUID player, String name) {
        List<Component> lines = new ArrayList<>();
        lines.add(this.lang.get(ChatMessages.CARD_NAME, Arg.text("name", name)));
        String rank = rank(player);
        if (!rank.isEmpty()) {
            lines.add(this.lang.get(ChatMessages.CARD_RANK, Arg.text("rank", rank)));
        }
        Optional<String> team = this.teams.teamName(player);
        lines.add(team.isPresent()
            ? this.lang.get(ChatMessages.CARD_TEAM, Arg.text("team", team.get()))
            : this.lang.get(ChatMessages.CARD_NO_TEAM));
        lines.add(this.lang.get(ChatMessages.CARD_BALANCE, Arg.money("balance", this.ledger.balance(player, Currency.MONEY))));
        lines.add(this.lang.get(ChatMessages.CARD_KILLS, Arg.number("kills", this.stats.get(player, StatsRecorder.Stat.KILLS))));
        lines.add(this.lang.get(ChatMessages.CARD_PLAYTIME,
            Arg.time("playtime", Duration.ofSeconds(Math.max(0, this.stats.get(player, StatsRecorder.Stat.PLAYTIME_SECONDS))))));
        lines.add(this.lang.get(ChatMessages.CARD_CLICK, Arg.text("name", name)));
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** A name in public chat: the card on hover (when enabled) and a click that suggests {@code /msg <name> }. */
    Component chatName(UUID player, String name, boolean withCard) {
        Component text = Component.text(name).clickEvent(ClickEvent.suggestCommand("/msg " + name + " "));
        return withCard ? text.hoverEvent(HoverEvent.showText(card(player, name))) : text;
    }

    /** A name in a private message: clicking it starts another message to that player (or a reply to the console). */
    Component messageName(String name, boolean console) {
        return Component.text(name)
            .clickEvent(ClickEvent.suggestCommand(console ? "/r " : "/msg " + name + " "))
            .hoverEvent(HoverEvent.showText(this.lang.get(ChatMessages.PM_NAME_HOVER, Arg.text("name", name))));
    }
}

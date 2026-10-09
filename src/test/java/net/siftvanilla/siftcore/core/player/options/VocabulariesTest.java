package net.siftvanilla.siftcore.core.player.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.Test;

class VocabulariesTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    @Test
    void alertStylesStoreLowercaseIdsInTheGivenOrder() {
        Choice<AlertStyle> style = Choices.alert("style", AlertStyle.ACTIONBAR, AlertStyle.ACTIONBAR, AlertStyle.CHAT, AlertStyle.BOTH)
            .text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("actionbar", "chat", "both"), style.optionIds());
        assertEquals(Optional.of(AlertStyle.BOTH), style.decode("Both"));
        assertEquals(OptionTexts.ALERT_BOTH, style.option("both").label(), "labels written once");
        assertTrue(AlertStyle.BOTH.chat());
        assertFalse(AlertStyle.ACTIONBAR.chat());
    }

    @Test
    void audiencesAndPingsUseTheSharedIds() {
        Choice<Audience> who = Choices.audience("who", Audience.EVERYONE, Audience.values()).text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("everyone", "friends-team", "friends", "nobody"), who.optionIds());
        assertTrue(Audience.FRIENDS_TEAM.needsFriends());
        assertFalse(Audience.NOBODY.needsFriends());
        Choice<PingSound> ping = Choices.ping("ping", PingSound.DEFAULT).text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("default", "bell", "pling", "chime", "off"), ping.optionIds(), "every sound when none are named");
        Choice<PingSound> team = Choices.ping("team", PingSound.OFF, PingSound.OFF, PingSound.BELL).text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("off", "bell"), team.optionIds());
    }

    @Test
    void confirmThresholdsParseMoneyPresets() {
        Choice<ConfirmAbove> confirm = Choices.confirmAbove("confirm", Currency.MONEY, true, "10k", "100K", "1m")
            .text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("server", "always", "10k", "100k", "1m", "never"), confirm.optionIds());
        assertEquals(ConfirmAbove.SERVER, confirm.defaultValue());
        ConfirmAbove tenK = confirm.decode("10K").orElseThrow();
        assertEquals(10_000, tenK.amount());
        assertEquals(List.of(Arg.money("amount", 10_000)), confirm.option("10k").args(), "the label shows the amount as money");
        assertTrue(tenK.asks(10_000, false));
        assertFalse(tenK.asks(9_999, true), "the player's preset decides");
        assertTrue(ConfirmAbove.SERVER.asks(5, true), "server follows the server's rule");
        assertFalse(ConfirmAbove.SERVER.asks(5_000_000, false));
        assertTrue(ConfirmAbove.ALWAYS.asks(1, false));
        assertFalse(ConfirmAbove.NEVER.asks(Long.MAX_VALUE, true));
        Choice<ConfirmAbove> pay = Choices.confirmAbove("pay", Currency.MONEY, false, "1k", "10k", "100k").text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("server", "always", "1k", "10k", "100k"), pay.optionIds(), "without never");
        Choice<ConfirmAbove> shards = Choices.confirmAbove("shards", Currency.SHARDS, true, "100", "1000", "5000").text(LABEL, DESCRIPTION).build();
        assertEquals(List.of(Arg.number("amount", 1_000)), shards.option("1000").args(), "plain numbers for shards");
        assertThrows(IllegalArgumentException.class, () -> ConfirmAbove.preset("lots"));
        assertThrows(IllegalArgumentException.class, () -> ConfirmAbove.preset("0"));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmAbove(ConfirmAbove.Kind.FROM, 0, "x"));
    }

    @Test
    void announcementFiltersParseMoneyPresets() {
        Choice<Announce> orders = Choices.announce("orders", Currency.MONEY, "1m", "10m", "100m").text(LABEL, DESCRIPTION).build();
        assertEquals(List.of("all", "1m", "10m", "100m", "off"), orders.optionIds());
        assertEquals(Announce.ALL, orders.defaultValue());
        Announce tenM = orders.decode("10m").orElseThrow();
        assertTrue(tenM.shows(10_000_000));
        assertFalse(tenM.shows(9_999_999));
        assertTrue(Announce.ALL.shows(1));
        assertFalse(Announce.OFF.shows(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Announce.preset("-5"));
    }

    @Test
    void autoAcceptKeepsTheFriendsFeatureIds() {
        assertEquals("nobody", AutoAccept.NOBODY.id());
        assertEquals("favourites", AutoAccept.FAVOURITES.id());
        assertEquals("all", AutoAccept.ALL.id());
        assertEquals("friends-team", AutoAccept.FRIENDS_TEAM.id());
    }
}

package net.siftvanilla.siftcore.feature.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.core.player.options.ConfirmAbove;
import org.junit.jupiter.api.Test;

class EconomyLogicTest {

    @Test
    void payLimitGrowsWithPlaytimeAndIsCapped() {
        assertEquals(250_000, PayLimits.limit(250_000, 50_000, 100_000_000, 0));
        assertEquals(750_000, PayLimits.limit(250_000, 50_000, 100_000_000, 10));
        assertEquals(100_000_000, PayLimits.limit(250_000, 50_000, 100_000_000, 1_000_000));
        assertEquals(100_000_000, PayLimits.limit(250_000, Long.MAX_VALUE / 2, 100_000_000, 10), "overflow saturates");
        assertEquals(250_000, PayLimits.limit(250_000, 50_000, 100_000_000, -5), "negative hours count as zero");
    }

    @Test
    void confirmationIsNeverLooserThanTheServer() {
        ConfirmAbove server = ConfirmAbove.SERVER;
        assertFalse(PayRules.asks(server, 99_999, 100_000), "below the server's amount");
        assertTrue(PayRules.asks(server, 100_000, 100_000), "at the server's amount");
        assertFalse(PayRules.asks(server, 5_000_000, 0), "the server's 0 never asks");
        assertTrue(PayRules.asks(ConfirmAbove.ALWAYS, 1, 100_000), "always");
        assertTrue(PayRules.asks(ConfirmAbove.ALWAYS, 1, 0), "always, even when the server never asks");
        ConfirmAbove from1k = EconomyFeature.PAY_CONFIRM_ABOVE.decodeOrNull("1k");
        assertFalse(PayRules.asks(from1k, 999, 100_000));
        assertTrue(PayRules.asks(from1k, 1_000, 100_000), "a lower amount than the server's asks sooner");
        assertTrue(PayRules.asks(from1k, 1_000, 0), "and works when the server never asks");
        ConfirmAbove from100k = EconomyFeature.PAY_CONFIRM_ABOVE.decodeOrNull("100k");
        assertTrue(PayRules.asks(from100k, 50_000, 10_000), "the server's lower amount still asks: never looser");
        assertFalse(PayRules.asks(from100k, 5_000, 10_000));
    }

    @Test
    void whoMayPayFollowsTheAudienceAndIgnores() {
        assertTrue(PayRules.accepts(Audience.EVERYONE, false, false, false, false));
        assertFalse(PayRules.accepts(Audience.NOBODY, true, true, false, false), "nobody means nobody, friends too");
        assertTrue(PayRules.accepts(Audience.FRIENDS, true, false, false, false));
        assertFalse(PayRules.accepts(Audience.FRIENDS, false, true, false, false), "teammates are not friends");
        assertTrue(PayRules.accepts(Audience.FRIENDS_TEAM, false, true, false, false));
        assertTrue(PayRules.accepts(Audience.FRIENDS_TEAM, true, false, false, false));
        assertFalse(PayRules.accepts(Audience.FRIENDS_TEAM, false, false, false, false));
        assertFalse(PayRules.accepts(Audience.EVERYONE, true, true, true, false), "an ignored player can never pay");
        assertTrue(PayRules.accepts(Audience.EVERYONE, false, false, true, true), "staff who can't be ignored still can");
        assertFalse(PayRules.accepts(Audience.NOBODY, false, false, true, true), "but not past nobody");
    }

    @Test
    void alertsFollowTheStyleAndTheMinimum() {
        assertTrue(PayRules.alerts(AlertStyle.CHAT, 1, 0));
        assertTrue(PayRules.alerts(AlertStyle.ACTIONBAR, 1_000, 1_000), "at the minimum");
        assertFalse(PayRules.alerts(AlertStyle.CHAT, 999, 1_000), "below the minimum: the money arrives quietly");
        assertFalse(PayRules.alerts(AlertStyle.OFF, Long.MAX_VALUE, 0));
    }

    @Test
    void theAwaySummaryListsTheBiggestPayersFirst() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        PayRules.Away away = PayRules.away(List.of(new PayRules.Payer(a, 500, 2), new PayRules.Payer(b, 9_000, 1),
            new PayRules.Payer(c, 100, 1), new PayRules.Payer(new UUID(0, 4), 0, 1)), 2);
        assertEquals(9_600, away.total(), "rows without an amount are left out");
        assertEquals(List.of(b, a), away.payers().stream().map(PayRules.Payer::payer).toList());
        assertEquals(1, away.more());
        assertTrue(PayRules.away(List.of(), 5).empty());
        PayRules.Away huge = PayRules.away(List.of(new PayRules.Payer(a, Long.MAX_VALUE, 1), new PayRules.Payer(b, 5, 1)), 5);
        assertEquals(Long.MAX_VALUE, huge.total(), "a total that would overflow saturates");
        assertEquals(0, huge.more());
    }

    @Test
    void hiddenAccountsComeFromOnlineValuesThenStoredChoicesThenTheDefault() {
        UUID online = new UUID(1, 1);
        UUID storedOn = new UUID(1, 2);
        UUID storedOff = new UUID(1, 3);
        UUID never = new UUID(1, 4);
        Map<UUID, Boolean> stored = Map.of(online, true, storedOn, true, storedOff, false);
        Predicate<UUID> hidden = HiddenAccounts.decide(stored, Map.of(online, false), false, false);
        assertFalse(hidden.test(online), "an online player's value (with permissions) wins over the stored row");
        assertTrue(hidden.test(storedOn));
        assertFalse(hidden.test(storedOff));
        assertFalse(hidden.test(never), "never chose: the default");
        Predicate<UUID> lockedOn = HiddenAccounts.decide(stored, Map.of(), true, true);
        assertTrue(lockedOn.test(storedOff), "a server lock overrides stored choices");
        assertTrue(lockedOn.test(never));
    }
}

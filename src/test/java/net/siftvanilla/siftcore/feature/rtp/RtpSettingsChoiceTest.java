package net.siftvanilla.siftcore.feature.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** "/rtp with no region" and "Confirm paid random teleports" decisions. */
class RtpSettingsChoiceTest {

    @Test
    void theLastRegionOnlyWhenChosenKnownAndUsable() {
        assertTrue(RtpDefault.LAST.straight(true, true));
        assertFalse(RtpDefault.LAST.straight(false, false), "nothing used yet: the picker");
        assertFalse(RtpDefault.LAST.straight(true, false), "turned off, forbidden or cooling down: the picker");
        assertFalse(RtpDefault.MENU.straight(true, true), "the default always opens the picker");
    }

    @Test
    void onlyATypedPaidTeleportAsksFirst() {
        assertTrue(RtpDefault.asksCost(true, 1_000, false));
        assertFalse(RtpDefault.asksCost(true, 0, false), "a free region never asks");
        assertFalse(RtpDefault.asksCost(true, 1_000, true), "the picker already shows the price");
        assertFalse(RtpDefault.asksCost(false, 1_000, false), "the player turned it off");
    }

    private static RtpSettings.Region region(String id, boolean enabled, long cost) {
        return new RtpSettings.Region(id, id, enabled, "world", null, cost, Duration.ZERO, 0, 0, 100, 1_000);
    }

    @Test
    void thePriceQuestionIsOfferedOnlyWhileARegionCostsMoney() {
        assertFalse(RtpFeature.anyPaidRegion(List.of()), "no regions");
        assertFalse(RtpFeature.anyPaidRegion(List.of(region("free", true, 0))), "free regions never ask");
        assertFalse(RtpFeature.anyPaidRegion(List.of(region("closed", false, 1_000), region("free", true, 0))),
            "a turned-off paid region can't be used");
        assertTrue(RtpFeature.anyPaidRegion(List.of(region("free", true, 0), region("ring", true, 1_000))));
    }

    @Test
    void storedValuesAreTheOptionIds() {
        assertEquals("last", RtpFeature.DEFAULT.encode(RtpDefault.LAST));
        assertEquals(RtpDefault.MENU, RtpFeature.DEFAULT.defaultValue());
        assertTrue(RtpFeature.CONFIRM_COST.defaultOn());
    }
}

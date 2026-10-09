package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What the audit log keeps of a staff teleport to someone's home (the base it led to). */
class HomesAuditTest {

    @Test
    void aStaffTeleportNamesTheHomeAndWhereItIs() {
        Home home = new Home("base", "world", -120.7, 63.0, 455.2, 0f, 0f, 1L);
        assertEquals("base world -121,63,455", HomesService.staffTeleportDetails(home));
    }
}
